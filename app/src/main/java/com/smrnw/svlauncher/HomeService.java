package com.smrnw.svlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Makes SV Launcher the home screen on devices that do not let the user choose one.
 *
 * 1. The Home key is intercepted and opens SV Launcher directly.
 * 2. If the stock launcher still comes to the foreground (from another app's
 *    "go home" action), SV Launcher is opened on top of it.
 * 3. After the TV starts, the system shows its stock launcher before it binds this
 *    service, so the window change above happens unseen. The first time the
 *    service connects after a boot, it opens SV Launcher itself.
 *
 * The service never inspects window content. It subscribes to window changes of the
 * stock launcher packages only, so it stays asleep while other apps are in use.
 */
public final class HomeService extends AccessibilityService {

    /** How long after a boot the stock launcher is replaced whatever window it shows. */
    private static final long BOOT_GRACE_MS = 3 * 60 * 1000L;
    /** A service bound later than this after boot was switched on by hand: no jump. */
    private static final long BOOT_CONNECT_LIMIT_MS = 10 * 60 * 1000L;
    /** Two "go home" calls closer than this are one and the same. */
    private static final long REPEAT_GAP_MS = 800L;

    private static final String PREFS = "home_service";
    private static final String KEY_HANDLED_BOOT = "handled_boot";

    /** "package/class" of every other home screen activity on the device. */
    private final HashSet<String> stockHomes = new HashSet<>();
    /** Stock launcher packages: their windows are the only events this service gets. */
    private final HashSet<String> stockPackages = new HashSet<>();
    /** Whether "package/class" is an activity of its package; class names are few, so cached. */
    private final HashMap<String, Boolean> isActivity = new HashMap<>();
    private long lastGoHome;

    /** Whether the user has switched the service on in the accessibility settings. */
    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        ComponentName mine = new ComponentName(context, HomeService.class);
        for (String part : enabled.split(":")) {
            if (mine.equals(ComponentName.unflattenFromString(part))) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        stockHomes.clear();
        stockPackages.clear();
        isActivity.clear();
        try {
            List<ResolveInfo> homes = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            for (ResolveInfo ri : homes) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
                stockHomes.add(ai.packageName + '/' + ai.name);
                stockPackages.add(ai.packageName);
            }
        } catch (RuntimeException ignored) {
            // Without the list the Home key still works; only the watching is lost.
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !stockPackages.isEmpty()) {
            info.packageNames = stockPackages.toArray(new String[0]);
            setServiceInfo(info);
        }
        if (firstConnectSinceBoot()) goHome();
    }

    /**
     * True once per boot, and only while the boot is recent: connecting later means
     * the user has just switched the service on, or it was restarted after an update.
     */
    private boolean firstConnectSinceBoot() {
        if (SystemClock.elapsedRealtime() > BOOT_CONNECT_LIMIT_MS) return false;
        String boot = bootId();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (boot.equals(prefs.getString(KEY_HANDLED_BOOT, ""))) return false;
        prefs.edit().putString(KEY_HANDLED_BOOT, boot).apply();
        return true;
    }

    /** Something that changes with every boot and stays the same until the next one. */
    private String bootId() {
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                return "count:" + Settings.Global.getInt(getContentResolver(), Settings.Global.BOOT_COUNT);
            } catch (Settings.SettingNotFoundException ignored) {
                // Fall through to the boot time.
            }
        }
        // Wall-clock time of the boot, to the minute: stable unless the clock is set.
        long bootTime = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        return "time:" + bootTime / 60000L;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkgName = event.getPackageName();
        CharSequence clsName = event.getClassName();
        if (pkgName == null) return;
        String pkg = pkgName.toString();
        if (!stockPackages.contains(pkg)) return;
        String cls = clsName != null ? clsName.toString() : "";

        boolean replace;
        if (stockHomes.contains(pkg + '/' + cls)) {
            replace = true;                      // the stock home screen itself
        } else if (SystemClock.elapsedRealtime() < BOOT_GRACE_MS) {
            // While the TV is starting, the stock launcher goes through splash and
            // loading windows; whichever of them is shown, it is not wanted.
            replace = true;
        } else {
            // Some launchers report a view class (a FrameLayout, ...) instead of their
            // activity. Those are their main window. Real screens of their own, such
            // as "All apps", are activities and stay untouched.
            replace = !isActivityOf(pkg, cls);
        }
        if (replace) goHome();
    }

    private boolean isActivityOf(String pkg, String cls) {
        if (cls.isEmpty()) return false;
        String key = pkg + '/' + cls;
        Boolean known = isActivity.get(key);
        if (known == null) {
            try {
                getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
                known = true;
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                known = false;
            }
            isActivity.put(key, known);
        }
        return known;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() != KeyEvent.KEYCODE_HOME) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) goHome();
        return true;
    }

    @Override
    public void onInterrupt() {
    }

    private void goHome() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastGoHome < REPEAT_GAP_MS) return;
        lastGoHome = now;
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
            // The system may refuse an activity start in rare states; next event retries.
        }
    }
}
