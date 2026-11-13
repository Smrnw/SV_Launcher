package com.smrnw.svlauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Updates from the GitHub releases of the project.
 *
 * The check asks only "what is the latest release", anonymously. The APK is taken
 * only from this repository's release downloads, and installed only if it is this
 * very app, signed with the same key and newer than what is installed. The system
 * installer always asks the user to confirm.
 */
final class Updater {

    static final String REPO = "Smrnw/SV_Launcher";

    private static final String TAG = "SVLauncher";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** The only place an APK is accepted from. */
    private static final String DOWNLOAD_PREFIX = "https://github.com/" + REPO + "/releases/download/";
    private static final long AUTO_INTERVAL_MS = 24 * 60 * 60 * 1000L;
    private static final long MAX_APK_BYTES = 60L * 1024 * 1024;
    private static final String APK_NAME = "update.apk";

    private static final String PREFS = "updates";
    private static final String KEY_AUTO = "auto";
    private static final String KEY_CHECKED = "checked";
    private static final String KEY_LATEST = "latest";
    private static final String KEY_URL = "url";
    private static final String KEY_SIZE = "size";

    static final String ACTION_INSTALL_STATUS = "com.smrnw.svlauncher.UPDATE_STATUS";

    /** The check finished; {@code ok} is false when GitHub could not be asked. Main thread. */
    interface CheckListener {
        void onChecked(boolean ok);
    }

    /** Download and install progress. Main thread. */
    interface InstallListener {
        /** Download progress, 0..100. */
        void onProgress(int percent);

        /** The system installer has the update (error is null), or what went wrong. */
        void onDone(String error);
    }

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static volatile int progress = -1;

    private Updater() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean autoCheck(Context context) {
        return sp(context).getBoolean(KEY_AUTO, true);
    }

    static void setAutoCheck(Context context, boolean on) {
        sp(context).edit().putBoolean(KEY_AUTO, on).apply();
    }

    /** Wall-clock time of the last successful check, 0 if none. */
    static long lastChecked(Context context) {
        return sp(context).getLong(KEY_CHECKED, 0L);
    }

    static String installedVersion(Context context) {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** The newer version found by the last check, or null when up to date. */
    static String available(Context context) {
        String latest = sp(context).getString(KEY_LATEST, "");
        return !latest.isEmpty() && compare(latest, installedVersion(context)) > 0
                && !sp(context).getString(KEY_URL, "").isEmpty() ? latest : null;
    }

    /** Download progress 0..100 while a download runs, otherwise -1. */
    static int progress() {
        return progress;
    }

    // -------------------------------------------------------------------- check

    /** The daily check, if it is due and switched on. Silent; listener may be null. */
    static void checkIfDue(Context context, CheckListener listener) {
        long age = System.currentTimeMillis() - lastChecked(context);
        if (autoCheck(context) && (age >= AUTO_INTERVAL_MS || age < 0)) check(context, listener);
    }

    static void check(Context context, final CheckListener listener) {
        final Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) return;
        Worker.io(() -> {
            boolean ok = false;
            try {
                ok = fetchLatest(app);
            } finally {
                BUSY.set(false);
            }
            final boolean result = ok;
            if (listener != null) Worker.MAIN.post(() -> listener.onChecked(result));
        });
    }

    private static boolean fetchLatest(Context app) {
        HttpURLConnection con = null;
        try {
            con = open(LATEST_URL);
            con.setRequestProperty("Accept", "application/vnd.github+json");
            int code = con.getResponseCode();
            if (code == 404) {
                save(app, "", "", 0); // no release published yet
                return true;
            }
            if (code != 200) {
                Log.w(TAG, "Update check: HTTP " + code);
                return false;
            }
            JSONObject release = new JSONObject(readText(con.getInputStream(), 512 * 1024));
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) return true;
            String tag = release.optString("tag_name");
            if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
            // The version ends up on screen, so take nothing but a plain number.
            if (!tag.matches("\\d{1,4}(\\.\\d{1,4}){1,3}")) tag = "";
            String url = "";
            long size = 0;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String u = a.optString("browser_download_url");
                if (u.startsWith(DOWNLOAD_PREFIX) && u.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    url = u;
                    size = a.optLong("size");
                    break;
                }
            }
            save(app, tag, url, size);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Update check failed: " + e);
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    private static void save(Context app, String latest, String url, long size) {
        sp(app).edit().putLong(KEY_CHECKED, System.currentTimeMillis())
                .putString(KEY_LATEST, latest).putString(KEY_URL, url).putLong(KEY_SIZE, size).apply();
    }

    // --------------------------------------------------------- download, install

    /** Whether Android lets this app hand an APK to the installer (Android 8+ asks the user). */
    static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < 26 || context.getPackageManager().canRequestPackageInstalls();
    }

    /** Downloads the update, checks it and passes it to the system installer. */
    static void downloadAndInstall(Context context, final InstallListener listener) {
        final Context app = context.getApplicationContext();
        if (!BUSY.compareAndSet(false, true)) return;
        progress = 0;
        Worker.io(() -> {
            String error;
            try {
                error = download(app, listener);
                if (error == null) error = install(app);
            } catch (Exception e) {
                Log.w(TAG, "Update failed: " + e);
                error = app.getString(R.string.update_error_network);
            } finally {
                progress = -1;
                BUSY.set(false);
            }
            final String result = error;
            Worker.MAIN.post(() -> listener.onDone(result));
        });
    }

    private static String download(Context app, final InstallListener listener) throws Exception {
        String url = sp(app).getString(KEY_URL, "");
        long expected = sp(app).getLong(KEY_SIZE, 0);
        if (!url.startsWith(DOWNLOAD_PREFIX)) return app.getString(R.string.update_error_network);
        File target = new File(app.getCacheDir(), APK_NAME);
        HttpURLConnection con = open(url);
        try {
            // GitHub redirects to its file storage; HttpURLConnection follows https to https.
            if (con.getResponseCode() != 200) return app.getString(R.string.update_error_network);
            long total = con.getContentLength() > 0 ? con.getContentLength() : expected;
            if (total > MAX_APK_BYTES) return app.getString(R.string.update_error_file);
            byte[] buf = new byte[32 * 1024];
            long done = 0;
            int lastPercent = -1;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(target)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > MAX_APK_BYTES) return app.getString(R.string.update_error_file);
                    out.write(buf, 0, n);
                    if (total > 0) {
                        final int percent = (int) Math.min(99, done * 100 / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            progress = percent;
                            Worker.MAIN.post(() -> listener.onProgress(percent));
                        }
                    }
                }
            }
            if (expected > 0 && done != expected) return app.getString(R.string.update_error_file);
        } finally {
            con.disconnect();
        }
        return verify(app, target);
    }

    /** Only this app, newer, signed with the same key. Anything else is thrown away. */
    @SuppressWarnings("deprecation")
    private static String verify(Context app, File apk) {
        PackageManager pm = app.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo update = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo mine;
        try {
            mine = pm.getPackageInfo(app.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            return app.getString(R.string.update_error_file);
        }
        if (update == null || !app.getPackageName().equals(update.packageName)) {
            apk.delete();
            return app.getString(R.string.update_error_file);
        }
        if (versionCode(update) <= versionCode(mine)) {
            apk.delete();
            return app.getString(R.string.update_error_older);
        }
        if (!Arrays.equals(signatures(update), signatures(mine))) {
            apk.delete();
            return app.getString(R.string.update_error_signature);
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Signature[] signatures(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= 28) {
            if (info.signingInfo == null) return new Signature[0];
            return info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getApkContentsSigners()
                    : info.signingInfo.getSigningCertificateHistory();
        }
        return info.signatures != null ? info.signatures : new Signature[0];
    }

    /** Streams the checked APK into an installer session; the system then asks the user. */
    private static String install(Context app) throws Exception {
        File apk = new File(app.getCacheDir(), APK_NAME);
        PackageInstaller installer = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(app.getPackageName());
        params.setSize(apk.length());
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            Intent status = new Intent(ACTION_INSTALL_STATUS).setPackage(app.getPackageName())
                    .setClass(app, UpdateReceiver.class);
            // The installer fills in the result, so the intent must stay mutable;
            // it is explicit, so nobody else can receive it.
            int piFlags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(app, id, status,
                    piFlags | PendingIntent.FLAG_UPDATE_CURRENT);
            HomeService.expectSelfUpdate(app, true);
            session.commit(pi.getIntentSender());
        } catch (Exception e) {
            HomeService.expectSelfUpdate(app, false);
            installer.abandonSession(id);
            throw e;
        } finally {
            apk.delete(); // the session keeps its own copy
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    /** Compares dotted version numbers: 1.10 is newer than 1.9. */
    static int compare(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int x = part(pa, i);
            int y = part(pb, i);
            if (x != y) return x < y ? -1 : 1;
        }
        return 0;
    }

    private static int part(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(30000);
        con.setRequestProperty("User-Agent", "SVLauncher");
        return con;
    }

    private static String readText(InputStream stream, int limit) throws Exception {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(16 * 1024);
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IllegalStateException("answer too large");
            }
            return out.toString("UTF-8");
        }
    }
}
