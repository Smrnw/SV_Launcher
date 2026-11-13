package com.smrnw.svlauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Where wallpapers come from and how they get on screen.
 *
 * Built-in: a few abstract 8K pictures in the APK assets. They are always there, so
 * the cloud and the user's own source fall back to them while they have nothing.
 *
 * Cloud: a public GitHub repository with one folder per theme. The user picks a
 * theme, or a single picture of it; "default" is used until they do. The list
 * comes from the GitHub contents API; every picture is stored under its git blob
 * hash, so a replaced one is fetched again, an unchanged one never is, and one
 * removed from the repository, or from the selection, disappears from the device.
 * The pictures themselves are fetched through the API too, by that hash.
 * raw.githubusercontent.com would be faster, but some providers block it while the
 * API stays reachable, so it is only the fallback.
 *
 * Own: pictures the user picked in settings (copied into private storage) plus
 * anything they dropped into the app folder on shared storage.
 *
 * A wallpaper is identified by a string: a file path, or {@link #ASSET} plus the
 * asset path for a built-in one.
 */
final class Wallpapers {

    /** owner/name of the public repository with the theme folders. */
    static final String REPO = "Smrnw/SV_Launcher-wallpapers";
    /** The theme used until the user picks another one. */
    static final String DEFAULT_THEME = "default";

    private static final String TAG = "SVLauncher";
    private static final String API = "https://api.github.com/repos/" + REPO;
    private static final String CONTENTS_URL = API + "/contents/";
    /** A file's content by its git hash; with the "raw" media type it is the bytes. */
    private static final String BLOB_URL = API + "/git/blobs/";
    /** The only other host image bytes are accepted from. */
    private static final String RAW_PREFIX = "https://raw.githubusercontent.com/";

    private static final String ASSET = "asset:";
    private static final String ASSET_DIR = "wallpapers";
    /** Marks the last complete sync; holds the selection it was made for. */
    private static final String STAMP = ".sync";

    private static final long SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L;
    /** Minimum pause between attempts while the last sync has not succeeded yet. */
    private static final long RETRY_INTERVAL_MS = 4 * 60 * 1000L;
    private static final long MAX_FILE_BYTES = 25L * 1024 * 1024;
    private static final int MAX_FILES = 40;
    private static final int MAX_NAME_LENGTH = 100;

    /** Runs on the main thread whenever the set of available wallpapers changes. */
    static volatile Runnable listener;

    /** Receives the number of imported pictures on the main thread. */
    interface ImportCallback {
        void onImported(int count);
    }

    /** Receives a list of names on the main thread; null when the cloud did not answer. */
    interface ListCallback {
        void onList(ArrayList<String> names);
    }

    private static final AtomicBoolean SYNCING = new AtomicBoolean();
    private static final Random RANDOM = new Random();
    private static volatile long lastAttempt;
    /** The asset list never changes while the app runs, so it is read once. */
    private static volatile String[] builtIn;

    private Wallpapers() {
    }

    // ------------------------------------------------------------------ storage

    private static File cloudDir(Context context) {
        return ensure(new File(context.getFilesDir(), "wallpapers"));
    }

    private static File ownDir(Context context) {
        return ensure(new File(context.getFilesDir(), "wallpapers_own"));
    }

    /**
     * Android/data/&lt;package&gt;/files/wallpapers on shared storage. The app needs
     * no permission for it, and the user can fill it over USB, ADB or a file manager.
     * May be null when shared storage is not mounted.
     */
    static File sharedDir(Context context) {
        try {
            return context.getExternalFilesDir("wallpapers");
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static File ensure(File dir) {
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    private static String ext(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.endsWith(".jpg")) return ".jpg";
        if (n.endsWith(".jpeg")) return ".jpeg";
        if (n.endsWith(".png")) return ".png";
        if (n.endsWith(".webp")) return ".webp";
        return null;
    }

    private static void addImages(File dir, List<String> out) {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && ext(f.getName()) != null) out.add(f.getPath());
        }
    }

    private static void addBuiltIn(Context context, List<String> out) {
        String[] names = builtIn;
        if (names == null) {
            try {
                names = context.getAssets().list(ASSET_DIR);
            } catch (Exception e) {
                names = null;
            }
            if (names == null) names = new String[0];
            builtIn = names;
        }
        for (String name : names) {
            if (ext(name) != null) out.add(ASSET + ASSET_DIR + '/' + name);
        }
    }

    /** Pictures of the selected source, or the built-in ones while it has none. */
    private static ArrayList<String> available(Context context) {
        ArrayList<String> out = new ArrayList<>();
        int mode = Prefs.wallpaperMode(context);
        if (mode == Prefs.WALLPAPER_OFF) return out;
        if (mode == Prefs.WALLPAPER_CLOUD) {
            addImages(cloudDir(context), out);
        } else if (mode == Prefs.WALLPAPER_OWN) {
            addImages(ownDir(context), out);
            addImages(sharedDir(context), out);
        }
        if (out.isEmpty()) addBuiltIn(context, out);
        return out;
    }

    static int ownCount(Context context) {
        ArrayList<String> out = new ArrayList<>();
        addImages(ownDir(context), out);
        addImages(sharedDir(context), out);
        return out.size();
    }

    /** True while {@code id} is still one of the pictures to show. */
    static boolean isAvailable(Context context, String id) {
        return available(context).contains(id);
    }

    /** A random picture other than {@code current}; null when wallpapers are off. */
    static String pick(Context context, String current) {
        ArrayList<String> ids = available(context);
        if (ids.isEmpty()) return null;
        if (ids.size() == 1) return ids.get(0);
        String id;
        do {
            id = ids.get(RANDOM.nextInt(ids.size()));
        } while (id.equals(current));
        return id;
    }

    // ----------------------------------------------------------------- decoding

    /**
     * Decodes straight to the screen size, never larger: an 8K picture on a 1080p
     * TV costs the same as a 1080p one, because the decoder itself skips the extra
     * pixels.
     *
     * Full colour on purpose: in RGB_565 smooth gradients break into visible rings.
     * The wallpaper is only held while the home screen is visible (see
     * HomeActivity.onTrimMemory). Not a hardware bitmap either, see {@link Apps#card}.
     */
    static Bitmap decode(Context context, String id, int reqW, int reqH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = open(context, id)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;

            // How much the picture has to shrink to just cover the screen.
            float scale = Math.max(reqW / (float) o.outWidth, reqH / (float) o.outHeight);
            int sample = 1;
            while (scale * sample * 2 <= 1f) sample *= 2;

            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            // inSampleSize only does powers of two; the density pair finishes the job.
            float rest = scale * sample;
            if (rest < 0.98f) {
                o.inScaled = true;
                o.inDensity = 1000;
                o.inTargetDensity = (int) Math.ceil(1000 * rest);
            }
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            try (InputStream in = open(context, id)) {
                return BitmapFactory.decodeStream(in, null, o);
            }
        } catch (OutOfMemoryError | Exception e) {
            Log.w(TAG, "Cannot decode wallpaper: " + e);
            return null;
        }
    }

    private static InputStream open(Context context, String id) throws Exception {
        if (id.startsWith(ASSET)) return context.getAssets().open(id.substring(ASSET.length()));
        // Buffered: the decoder reads in small pieces.
        return new BufferedInputStream(new FileInputStream(id), 64 * 1024);
    }

    // -------------------------------------------------------------- own pictures

    /**
     * Copies pictures chosen in the system picker into private storage. Nothing from
     * the Uri ends up in a file name, and anything that is not a decodable image of a
     * sane size is dropped.
     */
    static void importOwn(Context context, final List<Uri> uris, final ImportCallback callback) {
        final Context app = context.getApplicationContext();
        Worker.io(() -> {
            File dir = ownDir(app);
            ArrayList<String> existing = new ArrayList<>();
            addImages(dir, existing);
            int room = MAX_FILES - existing.size();
            int added = 0;
            long stamp = System.currentTimeMillis();
            for (int i = 0; i < uris.size() && added < room; i++) {
                File tmp = new File(dir, "import.tmp");
                try (InputStream in = app.getContentResolver().openInputStream(uris.get(i))) {
                    if (in == null || copy(in, tmp, null) <= 0) continue;
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(tmp.getPath(), o);
                    String ext = extForMime(o.outMimeType);
                    if (o.outWidth <= 0 || ext == null) continue;
                    if (tmp.renameTo(new File(dir, "own_" + stamp + "_" + i + ext))) added++;
                } catch (Exception ignored) {
                    // Unreadable source: skip it and carry on with the rest.
                } finally {
                    tmp.delete();
                }
            }
            final int count = added;
            Worker.MAIN.post(() -> callback.onImported(count));
        });
    }

    /** Removes pictures added through the picker. The shared folder is the user's to manage. */
    static void clearOwn(Context context) {
        final Context app = context.getApplicationContext();
        Worker.io(() -> {
            File[] files = ownDir(app).listFiles();
            if (files != null) {
                for (File f : files) f.delete();
            }
            notifyChanged();
        });
    }

    private static String extForMime(String mime) {
        if ("image/jpeg".equals(mime)) return ".jpg";
        if ("image/png".equals(mime)) return ".png";
        if ("image/webp".equals(mime)) return ".webp";
        return null;
    }

    // ------------------------------------------------------------- cloud themes

    /** Theme folders of the repository, for the settings dialog. */
    static void listThemes(ListCallback callback) {
        listNames("", true, callback);
    }

    /** Picture names inside one theme folder, for the settings dialog. */
    static void listPictures(String theme, ListCallback callback) {
        listNames(theme, false, callback);
    }

    private static void listNames(final String folder, final boolean dirs, final ListCallback callback) {
        Worker.io(() -> {
            ArrayList<String> names;
            try {
                JSONArray list = fetchList(folder);
                names = new ArrayList<>();
                for (int i = 0; list != null && i < list.length(); i++) {
                    JSONObject o = list.getJSONObject(i);
                    String name = o.optString("name");
                    String type = o.optString("type");
                    if (!isSafeName(name)) continue;
                    boolean fits = dirs ? "dir".equals(type) : "file".equals(type) && ext(name) != null;
                    if (fits) names.add(name);
                }
                Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
            } catch (Exception e) {
                Log.w(TAG, "Wallpaper themes: " + e);
                names = null;
            }
            final ArrayList<String> result = names;
            Worker.MAIN.post(() -> callback.onList(result));
        });
    }

    /**
     * A name that came from the network is shown on screen and goes into a URL, never
     * into a file name. Still, nothing odd is let through.
     */
    private static boolean isSafeName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.startsWith(".")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < ' ' || c == '/' || c == '\\') return false;
        }
        return true;
    }

    /**
     * The folder's listing from the contents API; null if it does not exist (404).
     * Throws when the server could not be asked or answered with an error.
     */
    private static JSONArray fetchList(String folder) throws Exception {
        HttpURLConnection con = open(CONTENTS_URL + Uri.encode(folder));
        con.setRequestProperty("Accept", "application/vnd.github+json");
        try {
            int code = con.getResponseCode();
            if (code == 404) return null;
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            String body = readText(con.getInputStream());
            // A file instead of a folder answers with an object, not a list.
            return body.trim().startsWith("[") ? new JSONArray(body) : null;
        } finally {
            con.disconnect();
        }
    }

    // -------------------------------------------------------------------- cloud

    /** "theme/picture", or "theme/" for a whole theme: what the cache must hold. */
    private static String selection(Context context) {
        return Prefs.cloudTheme(context) + '/' + Prefs.cloudPicture(context);
    }

    /**
     * Checks the repository. Does nothing unless the cloud source is selected, and
     * goes to the network at most once per {@link #SYNC_INTERVAL_MS} unless forced or
     * the selection changed since the last complete sync.
     */
    static void sync(Context context, final boolean force) {
        final Context app = context.getApplicationContext();
        if (Prefs.wallpaperMode(app) != Prefs.WALLPAPER_CLOUD) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && lastAttempt != 0 && now - lastAttempt < RETRY_INTERVAL_MS) return;
        if (!SYNCING.compareAndSet(false, true)) return;
        lastAttempt = now;
        Worker.io(() -> {
            try {
                File stamp = new File(cloudDir(app), STAMP);
                String selection = selection(app);
                long age = System.currentTimeMillis() - stamp.lastModified();
                // A negative age means the clock was moved back: sync rather than wait.
                boolean due = force || age >= SYNC_INTERVAL_MS || age < 0
                        || !selection.equals(readStamp(stamp));
                if (due) doSync(app, stamp, selection);
            } catch (Exception e) {
                // Offline or a malformed answer: keep what is already on the device.
                Log.w(TAG, "Wallpaper sync failed: " + e);
            } finally {
                SYNCING.set(false);
            }
        });
    }

    private static void doSync(Context app, File stamp, String selection) throws Exception {
        String theme = Prefs.cloudTheme(app);
        String picture = Prefs.cloudPicture(app);
        // null (404) means no such theme: nothing is wanted, the built-in pictures show.
        JSONArray list = fetchList(theme);

        // local file name -> { download url, size in bytes, git blob hash }
        LinkedHashMap<String, String[]> wanted = new LinkedHashMap<>();
        LinkedHashMap<String, String[]> chosen = new LinkedHashMap<>();
        for (int i = 0; list != null && i < list.length() && wanted.size() < MAX_FILES; i++) {
            JSONObject o = list.getJSONObject(i);
            String name = o.optString("name");
            String ext = ext(name);
            String sha = o.optString("sha");
            String url = o.optString("download_url");
            long size = o.optLong("size");
            if (ext == null || !"file".equals(o.optString("type"))) continue;
            // The hash becomes a file name, so it must be nothing but a hash.
            if (!sha.matches("[0-9a-f]{40}") || !url.startsWith(RAW_PREFIX)) continue;
            if (size <= 0 || size > MAX_FILE_BYTES) continue;
            String[] meta = {url, Long.toString(size), sha};
            wanted.put(sha + ext, meta);
            if (name.equals(picture)) chosen.put(sha + ext, meta);
        }
        // A single picture was chosen and it is still there: only that one. If it
        // was removed from the repository, the whole theme is better than nothing.
        if (!chosen.isEmpty()) wanted = chosen;

        File dir = cloudDir(app);
        boolean removed = false;
        File[] local = dir.listFiles();
        if (local != null) {
            for (File f : local) {
                String name = f.getName();
                if (!name.equals(STAMP) && !wanted.containsKey(name)) removed |= f.delete();
            }
        }
        if (removed) notifyChanged();

        boolean complete = true;
        for (Map.Entry<String, String[]> e : wanted.entrySet()) {
            File target = new File(dir, e.getKey());
            if (target.isFile()) continue;
            String[] meta = e.getValue();
            long size = Long.parseLong(meta[1]);
            String sha = meta[2];
            if (download(BLOB_URL + sha, size, sha, target)
                    || download(meta[0], size, sha, target)) {
                notifyChanged();
            } else {
                complete = false;
            }
        }
        // Only a fully successful pass counts; otherwise the next start retries.
        if (complete) writeStamp(stamp, selection);
    }

    private static String readStamp(File stamp) {
        if (!stamp.isFile() || stamp.length() > 1024) return "";
        try {
            return readText(new FileInputStream(stamp));
        } catch (Exception e) {
            return "";
        }
    }

    private static void writeStamp(File stamp, String selection) throws Exception {
        try (FileOutputStream out = new FileOutputStream(stamp)) {
            out.write(selection.getBytes("UTF-8"));
        }
        stamp.setLastModified(System.currentTimeMillis());
    }

    /** Downloads one picture and keeps it only if its git blob hash matches the listing. */
    private static boolean download(String url, long size, String sha, File target) {
        File tmp = new File(target.getPath() + ".tmp");
        HttpURLConnection con = null;
        try {
            con = open(url);
            // Ignored by raw.githubusercontent.com, needed by the blob API.
            con.setRequestProperty("Accept", "application/vnd.github.raw");
            int code = con.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "Wallpaper download: HTTP " + code + " from " + host(url));
                return false;
            }
            // git hashes a blob as sha1("blob <size>\0" + content)
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + size + '\0').getBytes("UTF-8"));
            long total;
            try (InputStream in = con.getInputStream()) {
                total = copy(in, tmp, digest);
            }
            if (total != size || !hex(digest.digest()).equals(sha)) {
                Log.w(TAG, "Wallpaper download: content does not match from " + host(url));
                return false;
            }
            return tmp.renameTo(target);
        } catch (Exception e) {
            Log.w(TAG, "Wallpaper download failed from " + host(url) + ": " + e);
            return false;
        } finally {
            if (con != null) con.disconnect();
            tmp.delete();
        }
    }

    /** Copies a stream to a file; returns the byte count, or -1 if it exceeds the size limit. */
    private static long copy(InputStream in, File target, MessageDigest digest) throws Exception {
        byte[] buf = new byte[16 * 1024];
        long total = 0;
        try (FileOutputStream out = new FileOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_FILE_BYTES) return -1;
                out.write(buf, 0, n);
                if (digest != null) digest.update(buf, 0, n);
            }
        }
        return total;
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    /** Only the host goes to the log, never the full address. */
    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception e) {
            return "?";
        }
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(15000);
        con.setReadTimeout(20000);
        con.setRequestProperty("User-Agent", "SVLauncher");
        return con;
    }

    private static String readText(InputStream stream) throws Exception {
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8 * 1024);
            byte[] buf = new byte[8 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) throw new IllegalStateException("list too large");
            }
            return out.toString("UTF-8");
        }
    }

    private static void notifyChanged() {
        Worker.MAIN.post(() -> {
            Runnable l = listener;
            if (l != null) l.run();
        });
    }
}
