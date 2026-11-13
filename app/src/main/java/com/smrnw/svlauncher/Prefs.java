package com.smrnw.svlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

/** Everything the launcher remembers between runs. */
final class Prefs {

    static final int WALLPAPER_CLOUD = 0;
    static final int WALLPAPER_OWN = 1;
    static final int WALLPAPER_OFF = 2;
    static final int WALLPAPER_BUILTIN = 3;

    private static final String FILE = "launcher";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_CLOUD_THEME = "cloud_theme";
    private static final String KEY_CLOUD_PICTURE = "cloud_picture";
    private static final String KEY_STATS = "stats";
    private static final String KEY_NAV_SOUND = "nav_sound";
    private static final String KEY_SETUP_DONE = "setup_done";

    /**
     * Grows with every write. The home screen compares it with the value it saw
     * last to find out whether the settings screen changed anything meanwhile.
     */
    static volatile int revision;

    private Prefs() {
    }

    private static SharedPreferences sp(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Pinned packages in the order the user arranged them. */
    static ArrayList<String> pinned(Context context) {
        ArrayList<String> out = new ArrayList<>();
        split(sp(context).getString(KEY_PINNED, ""), out);
        return out;
    }

    static void setPinned(Context context, Collection<String> packages) {
        put(context, KEY_PINNED, TextUtils.join("\n", packages));
    }

    static HashSet<String> hidden(Context context) {
        HashSet<String> out = new HashSet<>();
        split(sp(context).getString(KEY_HIDDEN, ""), out);
        return out;
    }

    static void setHidden(Context context, Collection<String> packages) {
        put(context, KEY_HIDDEN, TextUtils.join("\n", packages));
    }

    static int wallpaperMode(Context context) {
        int mode = sp(context).getInt(KEY_WALLPAPER, WALLPAPER_CLOUD);
        return mode < WALLPAPER_CLOUD || mode > WALLPAPER_BUILTIN ? WALLPAPER_CLOUD : mode;
    }

    static void setWallpaperMode(Context context, int mode) {
        sp(context).edit().putInt(KEY_WALLPAPER, mode).apply();
        revision++;
    }

    /** Theme folder of the cloud collection. */
    static String cloudTheme(Context context) {
        return sp(context).getString(KEY_CLOUD_THEME, Wallpapers.DEFAULT_THEME);
    }

    /** A single picture of the theme, or "" for the whole theme. */
    static String cloudPicture(Context context) {
        return sp(context).getString(KEY_CLOUD_PICTURE, "");
    }

    static void setCloud(Context context, String theme, String picture) {
        sp(context).edit().putString(KEY_CLOUD_THEME, theme)
                .putString(KEY_CLOUD_PICTURE, picture).apply();
        revision++;
    }

    /** Click sound while moving through the apps with the remote. */
    static boolean navSound(Context context) {
        return sp(context).getBoolean(KEY_NAV_SOUND, true);
    }

    static void setNavSound(Context context, boolean on) {
        sp(context).edit().putBoolean(KEY_NAV_SOUND, on).apply();
        revision++;
    }

    /** Bit mask of {@link SystemStats} indicators shown on the home screen. */
    static int statsMask(Context context) {
        return sp(context).getInt(KEY_STATS, SystemStats.ALL) & SystemStats.ALL;
    }

    static void setStatsMask(Context context, int mask) {
        sp(context).edit().putInt(KEY_STATS, mask & SystemStats.ALL).apply();
        revision++;
    }

    /** True once the first-start wizard has been completed. */
    static boolean setupDone(Context context) {
        return sp(context).getBoolean(KEY_SETUP_DONE, false);
    }

    static void setSetupDone(Context context) {
        sp(context).edit().putBoolean(KEY_SETUP_DONE, true).apply();
        revision++;
    }

    private static void put(Context context, String key, String value) {
        sp(context).edit().putString(key, value).apply();
        revision++;
    }

    // Package names cannot contain a line break, so it is a safe separator.
    private static void split(String joined, Collection<String> out) {
        if (joined == null || joined.isEmpty()) return;
        for (String s : joined.split("\n")) {
            if (!s.isEmpty()) out.add(s);
        }
    }
}
