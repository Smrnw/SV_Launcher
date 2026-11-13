package com.smrnw.svlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.widget.Toast;

import java.util.List;

/**
 * Developer options on TVs whose firmware hides them (Yandex TV and other
 * operator builds remove the menu entry or block the "tap build number seven
 * times" trick).
 *
 * The screen itself usually stays in the firmware, so it is opened directly by its
 * action or its class name. If the user once gave the launcher the
 * WRITE_SECURE_SETTINGS permission over ADB, the mode is also switched on here.
 * An ordinary app cannot switch it on in any other way.
 */
final class DeveloperMode {

    /** Where the developer options live on known firmwares, the standard way first. */
    private static final Intent[] SCREENS = {
            new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
            new Intent("com.android.settings.APPLICATION_DEVELOPMENT_SETTINGS"),
            component("com.android.tv.settings", "com.android.tv.settings.system.development.DevelopmentActivity"),
            component("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"),
            component("com.android.settings", "com.android.settings.Settings$DevelopmentSettingsActivity"),
            component("com.android.settings", "com.android.settings.DevelopmentSettings"),
    };

    private DeveloperMode() {
    }

    private static Intent component(String pkg, String cls) {
        return new Intent().setComponent(new ComponentName(pkg, cls));
    }

    static boolean isEnabled(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) != 0;
    }

    /** True only if the permission was given over ADB; it is never asked for. */
    private static boolean canSwitch(Context context) {
        return context.checkCallingOrSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Switches the mode on where allowed, then opens its screen or explains what else works. */
    static void open(final Activity activity) {
        if (!isEnabled(activity) && canSwitch(activity)) {
            try {
                Settings.Global.putInt(activity.getContentResolver(),
                        Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 1);
                Toast.makeText(activity, R.string.dev_enabled, Toast.LENGTH_SHORT).show();
            } catch (SecurityException ignored) {
                // The permission was taken back; the screen is still worth a try.
            }
        }
        for (Intent screen : SCREENS) {
            Intent target = resolve(activity, screen);
            if (target == null) continue;
            try {
                activity.startActivity(target);
                return;
            } catch (RuntimeException ignored) {
                // Not allowed after all; try the next one.
            }
        }

        // Nothing reachable: the build number trick and ADB are what is left.
        final Intent about = DefaultLauncher.real(activity, new Intent(Settings.ACTION_DEVICE_INFO_SETTINGS));
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.dev_title)
                .setMessage(R.string.dev_manual)
                .setNegativeButton(android.R.string.cancel, null);
        if (about != null) {
            dialog.setPositiveButton(R.string.dev_about, (d, which) -> {
                try {
                    activity.startActivity(about);
                } catch (RuntimeException e) {
                    Toast.makeText(activity, R.string.cant_open, Toast.LENGTH_SHORT).show();
                }
            });
        }
        dialog.show();
    }

    /**
     * An exported, real (not a Google TV stub) activity for the intent, or null.
     * Exported is checked here because a hidden menu is sometimes hidden exactly
     * by closing the screen to other apps.
     */
    private static Intent resolve(Context context, Intent intent) {
        List<ResolveInfo> handlers;
        try {
            handlers = context.getPackageManager().queryIntentActivities(intent, 0);
        } catch (RuntimeException e) {
            return null;
        }
        for (ResolveInfo ri : handlers) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !ai.exported || !ai.enabled || DefaultLauncher.isStub(ai)) continue;
            return new Intent(intent).setClassName(ai.packageName, ai.name);
        }
        return null;
    }
}
