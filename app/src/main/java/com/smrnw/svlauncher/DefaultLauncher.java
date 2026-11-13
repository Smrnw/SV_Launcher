package com.smrnw.svlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/**
 * The two ways of becoming the home screen, and the system screens they need.
 *
 * 1. The system choice of the home app (Android 10+: the "home" role). Clean, but
 *    some TVs, Google TV in particular, still send the Home button to their own
 *    launcher.
 * 2. Our accessibility service, which catches the Home button itself.
 *
 * Google TV ships "stub" activities for screens it does not have (accessibility
 * settings, the file picker, ...): the intent resolves, but all the stub does is
 * show an error. Such handlers are treated as missing.
 */
final class DefaultLauncher {

    private DefaultLauncher() {
    }

    // ------------------------------------------------------------ system choice

    /** Whether the system already treats SV Launcher as the home app. */
    static boolean isDefault(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                return roles.isRoleHeld(RoleManager.ROLE_HOME);
            }
        }
        ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY);
        return home != null && home.activityInfo != null
                && context.getPackageName().equals(home.activityInfo.packageName);
    }

    /**
     * Opens the system question "make SV Launcher the home app?", or the home app
     * list where there is no such question. Returns false if neither exists.
     */
    static boolean requestDefault(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = activity.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                Intent ask = real(activity, roles.createRequestRoleIntent(RoleManager.ROLE_HOME));
                if (ask != null && start(activity, ask, requestCode)) return true;
            }
        }
        Intent list = real(activity, new Intent(Settings.ACTION_HOME_SETTINGS));
        return list != null && start(activity, list, -1);
    }

    // ------------------------------------------------------------ the service

    /** Whether the user has switched our accessibility service on. */
    static boolean serviceEnabled(Context context) {
        return HomeService.isEnabled(context);
    }

    /**
     * Opens the accessibility settings where they really exist. Otherwise explains
     * where to find them by hand, with a button to the main settings screen.
     */
    static void openServiceSettings(final Activity activity) {
        Intent direct = real(activity, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        if (direct != null && start(activity, direct, -1)) return;

        final Intent settings = real(activity, new Intent(Settings.ACTION_SETTINGS));
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.service_title)
                .setMessage(R.string.service_manual)
                .setNegativeButton(android.R.string.cancel, null);
        if (settings != null) {
            dialog.setPositiveButton(R.string.service_open_settings,
                    (d, which) -> start(activity, settings, -1));
        }
        dialog.show();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * An explicit intent for the first handler of {@code intent} that is not a
     * stub, or null if there is none.
     */
    static Intent real(Context context, Intent intent) {
        List<ResolveInfo> handlers;
        try {
            handlers = context.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
        } catch (RuntimeException e) {
            return null;
        }
        for (ResolveInfo ri : handlers) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || isStub(ai)) continue;
            return new Intent(intent).setClassName(ai.packageName, ai.name);
        }
        return null;
    }

    private static boolean isStub(ActivityInfo ai) {
        String pkg = ai.packageName.toLowerCase(java.util.Locale.US);
        String cls = ai.name.toLowerCase(java.util.Locale.US);
        return pkg.contains("stub") || cls.contains("stub");
    }

    private static boolean start(Activity activity, Intent intent, int requestCode) {
        try {
            if (requestCode >= 0) {
                activity.startActivityForResult(intent, requestCode);
            } else {
                activity.startActivity(intent);
            }
            return true;
        } catch (RuntimeException e) {
            Toast.makeText(activity, R.string.cant_open, Toast.LENGTH_SHORT).show();
            return false;
        }
    }
}
