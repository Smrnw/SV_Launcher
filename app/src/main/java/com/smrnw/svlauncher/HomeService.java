package com.smrnw.svlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashSet;
import java.util.List;

/**
 * Makes SV Launcher the home screen on devices that do not let the user choose one.
 *
 * 1. The Home key is intercepted and opens SV Launcher directly.
 * 2. If the stock launcher still comes to the foreground (after boot, from another
 *    app's "go home" action), SV Launcher is opened on top of it.
 *
 * The service never inspects window content. It subscribes to window changes of the
 * stock launcher packages only, so it stays asleep while other apps are in use.
 */
public final class HomeService extends AccessibilityService {

    /** "package/class" of every other home screen activity on the device. */
    private final HashSet<String> stockHomes = new HashSet<>();

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
        HashSet<String> packages = new HashSet<>();
        try {
            List<ResolveInfo> homes = getPackageManager().queryIntentActivities(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
            for (ResolveInfo ri : homes) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
                stockHomes.add(ai.packageName + '/' + ai.name);
                packages.add(ai.packageName);
            }
        } catch (RuntimeException ignored) {
        }
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null && !packages.isEmpty()) {
            info.packageNames = packages.toArray(new String[0]);
            setServiceInfo(info);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        CharSequence cls = event.getClassName();
        if (pkg == null || cls == null) return;
        if (stockHomes.contains(pkg.toString() + '/' + cls)) goHome();
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
        try {
            startActivity(new Intent(this, HomeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (RuntimeException ignored) {
        }
    }
}
