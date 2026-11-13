package com.smrnw.svlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Everything the launcher remembers between runs. */
final class Prefs {

    static final int WALLPAPER_CLOUD = 0;
    static final int WALLPAPER_OWN = 1;
    static final int WALLPAPER_OFF = 2;
    static final int WALLPAPER_BUILTIN = 3;

    /** Minutes between wallpaper changes; 0 means "only when the home screen opens". */
    static final int[] WALLPAPER_INTERVALS = {0, 1, 5, 15, 60};
    /** Extra darkening of the wallpaper, in percent, by level. */
    static final int[] WALLPAPER_DIM = {0, 25, 45, 65};

    private static final String FILE = "launcher";
    private static final String KEY_PINNED = "pinned";
    private static final String KEY_HIDDEN = "hidden";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_SHOW_RECENT = "show_recent";
    private static final String KEY_LABELS = "labels";
    private static final String KEY_WALLPAPER = "wallpaper";
    private static final String KEY_WALLPAPER_INTERVAL = "wallpaper_interval";
    private static final String KEY_WALLPAPER_DIM = "wallpaper_dim";
    private static final String KEY_CLOUD_THEME = "cloud_theme";
    private static final String KEY_CLOUD_PICTURE = "cloud_picture";
    private static final String KEY_STATS = "stats";
    private static final String KEY_NAV_SOUND = "nav_sound";
    private static final String KEY_JUNK_CHECKED = "junk_checked";
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

    // --------------------------------------------------------------------- apps

    /** Pinned packages in the order the user arranged them. */
    static ArrayList<String> pinned(Context context) {
        return list(context, KEY_PINNED);
    }

    static void setPinned(Context context, Collection<String> packages) {
        putLines(context, KEY_PINNED, packages);
    }

    static HashSet<String> hidden(Context context) {
        return new HashSet<>(list(context, KEY_HIDDEN));
    }

    static void setHidden(Context context, Collection<String> packages) {
        putLines(context, KEY_HIDDEN, packages);
    }

    /** Recently launched packages, newest first. */
    static ArrayList<String> recent(Context context) {
        return list(context, KEY_RECENT);
    }

    static void setRecent(Context context, Collection<String> packages) {
        putLines(context, KEY_RECENT, packages);
    }

    static boolean showRecent(Context context) {
        return sp(context).getBoolean(KEY_SHOW_RECENT, true);
    }

    static void setShowRecent(Context context, boolean show) {
        sp(context).edit().putBoolean(KEY_SHOW_RECENT, show).apply();
        revision++;
    }

    /** Names the user gave to apps: package -> label. */
    static HashMap<String, String> labels(Context context) {
        HashMap<String, String> out = new HashMap<>();
        for (String line : list(context, KEY_LABELS)) {
            int tab = line.indexOf('\t');
            if (tab > 0 && tab < line.length() - 1) {
                out.put(line.substring(0, tab), line.substring(tab + 1));
            }
        }
        return out;
    }

    static void setLabels(Context context, Map<String, String> labels) {
        ArrayList<String> lines = new ArrayList<>(labels.size());
        for (Map.Entry<String, String> e : labels.entrySet()) {
            lines.add(e.getKey() + '\t' + clean(e.getValue()));
        }
        putLines(context, KEY_LABELS, lines);
    }

    /** True once the "hide nameless system apps?" question has been asked. */
    static boolean junkChecked(Context context) {
        return sp(context).getBoolean(KEY_JUNK_CHECKED, false);
    }

    static void setJunkChecked(Context context) {
        sp(context).edit().putBoolean(KEY_JUNK_CHECKED, true).apply();
    }

    /** True once the first-start wizard has been completed. */
    static boolean setupDone(Context context) {
        return sp(context).getBoolean(KEY_SETUP_DONE, false);
    }

    static void setSetupDone(Context context) {
        sp(context).edit().putBoolean(KEY_SETUP_DONE, true).apply();
        revision++;
    }

    // ---------------------------------------------------------------- wallpaper

    static int wallpaperMode(Context context) {
        return bounded(context, KEY_WALLPAPER, WALLPAPER_CLOUD, WALLPAPER_BUILTIN, WALLPAPER_CLOUD);
    }

    static void setWallpaperMode(Context context, int mode) {
        putInt(context, KEY_WALLPAPER, mode);
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

    /** Index into {@link #WALLPAPER_INTERVALS}. */
    static int wallpaperInterval(Context context) {
        return bounded(context, KEY_WALLPAPER_INTERVAL, 0, WALLPAPER_INTERVALS.length - 1, 2);
    }

    static void setWallpaperInterval(Context context, int index) {
        putInt(context, KEY_WALLPAPER_INTERVAL, index);
    }

    /** Index into {@link #WALLPAPER_DIM}. */
    static int wallpaperDim(Context context) {
        return bounded(context, KEY_WALLPAPER_DIM, 0, WALLPAPER_DIM.length - 1, 0);
    }

    static void setWallpaperDim(Context context, int level) {
        putInt(context, KEY_WALLPAPER_DIM, level);
    }

    // --------------------------------------------------------------- indicators

    /** Bit mask of {@link SystemStats} indicators shown on the home screen. */
    static int statsMask(Context context) {
        return sp(context).getInt(KEY_STATS, SystemStats.ALL) & SystemStats.ALL;
    }

    static void setStatsMask(Context context, int mask) {
        putInt(context, KEY_STATS, mask & SystemStats.ALL);
    }

    /** Click sound while moving through the apps with the remote. */
    static boolean navSound(Context context) {
        return sp(context).getBoolean(KEY_NAV_SOUND, true);
    }

    static void setNavSound(Context context, boolean on) {
        sp(context).edit().putBoolean(KEY_NAV_SOUND, on).apply();
        revision++;
    }

    // ------------------------------------------------------------------ helpers

    /** A stored number that is only trusted while it lies inside its valid range. */
    private static int bounded(Context context, String key, int min, int max, int fallback) {
        int value = sp(context).getInt(key, fallback);
        return value < min || value > max ? fallback : value;
    }

    private static void putInt(Context context, String key, int value) {
        sp(context).edit().putInt(key, value).apply();
        revision++;
    }

    // Lists are stored as one string, a line per item. Package names cannot
    // contain a line break, and free text is cleaned before it gets here.
    private static ArrayList<String> list(Context context, String key) {
        ArrayList<String> out = new ArrayList<>();
        String joined = sp(context).getString(key, "");
        if (joined == null || joined.isEmpty()) return out;
        for (String s : joined.split("\n")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    private static void putLines(Context context, String key, Collection<String> lines) {
        sp(context).edit().putString(key, TextUtils.join("\n", lines)).apply();
        revision++;
    }

    /** Free text typed by the user, made safe for the line-based storage. */
    static String clean(String text) {
        return text.replace('\n', ' ').replace('\t', ' ').trim();
    }
}
