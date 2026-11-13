package com.smrnw.svlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;

/**
 * Launcher settings as a side panel on the right, the way TV system menus look:
 * the home screen stays visible next to it. The first page lists topics, each
 * topic opens its own page, Back returns to the list. Every row is a short title
 * with a one-line state under it; pages are simply rebuilt after each change.
 */
public final class SettingsActivity extends Activity {

    private static final int REQUEST_PICK_IMAGES = 1;
    private static final int REQUEST_HOME_ROLE = 2;
    private static final int REQUEST_READ_IMAGES = 3;

    private static final int PAGE_MAIN = 0;
    private static final int PAGE_DEFAULT = 1;
    private static final int PAGE_WALLPAPER = 2;
    private static final int PAGE_HOME = 3;
    private static final int PAGE_APPS = 4;

    /** At most this many pictures are offered by the built-in picker. */
    private static final int MAX_PICKER_ITEMS = 300;
    private static final String STATE_PAGE = "page";

    /** The wallpaper sources in the order they are offered, and their names. */
    private static final int[] SOURCES = {Prefs.WALLPAPER_BUILTIN, Prefs.WALLPAPER_CLOUD,
            Prefs.WALLPAPER_OWN, Prefs.WALLPAPER_OFF};
    private static final int[] SOURCE_NAMES = {R.string.source_builtin, R.string.source_cloud,
            R.string.source_own, R.string.source_off};

    /** The clock in the header moves on while the panel is open. */
    private static final long CLOCK_TICK_MS = 15 * 1000L;

    private ViewGroup rows;
    private TextView brand;
    private TextView title;
    private View systemButton;
    private int page = PAGE_MAIN;
    /** Focusable tiles and rows of the page in screen order; focus is restored by index. */
    private final ArrayList<View> focusables = new ArrayList<>();
    /** Where the focus was on the main page before a topic was opened. */
    private int mainFocus;
    /** The user went to allow app installs; the update continues on return. */
    private boolean installAfterPermission;
    /** State line of the update row, rewritten while the download runs. */
    private TextView updateSummary;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            if (page == PAGE_MAIN) showClock();
            rows.postDelayed(this, CLOCK_TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A narrow window docked to the right edge, full height.
        getWindow().setLayout(getResources().getDimensionPixelSize(R.dimen.side_panel_width),
                ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().setGravity(Gravity.END);
        setContentView(R.layout.settings);
        rows = findViewById(R.id.rows);
        brand = findViewById(R.id.settings_brand);
        title = findViewById(R.id.settings_title);
        systemButton = findViewById(R.id.settings_system);
        systemButton.setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        if (savedInstanceState != null) page = savedInstanceState.getInt(STATE_PAGE, PAGE_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also picks up what changed in system screens opened from here.
        show(page, focusedRow());
        rows.postDelayed(clockTick, CLOCK_TICK_MS);
        if (installAfterPermission) {
            installAfterPermission = false;
            if (Updater.canInstall(this)) {
                startUpdate();
            } else {
                // Some TVs (Xiaomi among them) show an empty list on that screen,
                // so the user has no switch to turn on. Say what else works.
                new AlertDialog.Builder(this)
                        .setTitle(R.string.update_permission_title)
                        .setMessage(R.string.update_permission_denied)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        rows.removeCallbacks(clockTick);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, page);
    }

    @Override
    public void onBackPressed() {
        if (page == PAGE_MAIN) {
            super.onBackPressed();
        } else {
            // Back to the topic list, with the focus on the topic just left.
            show(PAGE_MAIN, mainFocus);
        }
    }

    @Override
    public void finish() {
        super.finish();
        // The panel slides back out to the right.
        overridePendingTransition(0, R.anim.panel_out);
    }

    // -------------------------------------------------------------------- pages

    /** Rebuilds the page; {@code focus} is the index of the row to focus. */
    private void show(int newPage, int focus) {
        page = newPage;
        rows.removeViews(1, rows.getChildCount() - 1); // index 0 is the header
        focusables.clear();
        updateSummary = null;
        boolean main = page == PAGE_MAIN;
        systemButton.setVisibility(main ? View.VISIBLE : View.GONE);
        if (!main) brand.setText(R.string.app_name);
        switch (page) {
            case PAGE_DEFAULT:
                buildDefault();
                break;
            case PAGE_WALLPAPER:
                buildWallpaper();
                break;
            case PAGE_HOME:
                buildHome();
                break;
            case PAGE_APPS:
                buildApps();
                break;
            default:
                buildMain();
                break;
        }
        // Some TVs do not focus anything in a new window by themselves; without a
        // highlighted row the remote would seem dead.
        if (!focusables.isEmpty()) {
            focusables.get(Math.max(0, Math.min(focus, focusables.size() - 1))).requestFocus();
        }
    }

    /** Index of the focused tile or row; the gear in the header counts as the first. */
    private int focusedRow() {
        int index = focusables.indexOf(getCurrentFocus());
        return Math.max(index, 0);
    }

    private void refresh() {
        show(page, focusedRow());
    }

    /** Opens a topic page and remembers where to come back to. */
    private void openPage(int newPage) {
        mainFocus = focusedRow();
        show(newPage, 0);
    }

    /** Adds a row: a title and a one-line state under it. */
    private View addRow(int titleRes, CharSequence summary, View.OnClickListener onClick) {
        return addRow(0, titleRes, summary, false, onClick);
    }

    /**
     * Adds a row with an icon (0 for none); {@code opensPage} adds the arrow that
     * marks rows leading to a page of their own.
     */
    private View addRow(int icon, int titleRes, CharSequence summary, boolean opensPage,
                        View.OnClickListener onClick) {
        View row = getLayoutInflater().inflate(R.layout.settings_row, rows, false);
        if (icon != 0) {
            ImageView iconView = row.findViewById(R.id.row_icon);
            iconView.setImageResource(icon);
            iconView.setVisibility(View.VISIBLE);
        }
        if (opensPage) row.findViewById(R.id.row_chevron).setVisibility(View.VISIBLE);
        ((TextView) row.findViewById(R.id.row_title)).setText(titleRes);
        TextView summaryView = row.findViewById(R.id.row_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        row.setOnClickListener(onClick);
        rows.addView(row);
        focusables.add(row);
        return row;
    }

    private void addSection(int titleRes) {
        TextView section = (TextView) getLayoutInflater().inflate(R.layout.settings_section, rows, false);
        section.setText(titleRes);
        rows.addView(section);
    }

    /**
     * The main page, laid out like the quick settings of Google TV: the clock, then
     * tiles two to a line. Updates come first on a tile of double width, then the
     * TV settings people reach for most, then the launcher's own topics.
     */
    private void buildMain() {
        showClock();
        int gap = getResources().getDimensionPixelSize(R.dimen.tile_gap);

        View update = makeTile(R.drawable.ic_update,
                getString(R.string.update_dialog_title, Updater.installedVersion(this)), updateState(),
                v -> onUpdateClicked());
        updateSummary = update.findViewById(R.id.tile_summary);
        LinearLayout.LayoutParams wide = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wide.bottomMargin = gap;
        rows.addView(update, wide);

        ArrayList<View> tv = new ArrayList<>(8);
        addTile(tv, R.drawable.ic_wifi, R.string.qs_wifi, networkState(),
                real(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        // Google TV has no Bluetooth screen to open directly, but it has the
        // pairing screen for remotes and accessories.
        addTile(tv, R.drawable.ic_bluetooth, R.string.qs_bluetooth, null,
                real(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                        new Intent("com.google.android.intent.action.CONNECT_INPUT")));
        addTile(tv, R.drawable.ic_picture, R.string.qs_picture, null,
                real(new Intent(Settings.ACTION_DISPLAY_SETTINGS)));
        addTile(tv, R.drawable.ic_sound, R.string.qs_sound, null,
                real(new Intent(Settings.ACTION_SOUND_SETTINGS)));
        addTile(tv, R.drawable.ic_accessibility, R.string.qs_accessibility, null,
                real(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        addTile(tv, R.drawable.ic_storage, R.string.qs_storage, null,
                real(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)));
        tv.add(makeTile(R.drawable.ic_developer, getText(R.string.dev_title),
                getText(DeveloperMode.isEnabled(this) ? R.string.dev_on : R.string.dev_hidden),
                v -> DeveloperMode.open(this)));
        addTileLines(tv, gap);

        addSection(R.string.section_launcher);
        ArrayList<View> launcher = new ArrayList<>(6);
        launcher.add(makeTile(R.drawable.ic_home, getText(R.string.default_title), defaultState(),
                v -> openPage(PAGE_DEFAULT)));
        launcher.add(makeTile(R.drawable.ic_wallpaper, getText(R.string.cat_wallpaper),
                getText(SOURCE_NAMES[indexOf(SOURCES, Prefs.wallpaperMode(this))]),
                v -> openPage(PAGE_WALLPAPER)));
        launcher.add(makeTile(R.drawable.ic_tv, getText(R.string.cat_home), statsState(),
                v -> openPage(PAGE_HOME)));
        launcher.add(makeTile(R.drawable.ic_apps, getText(R.string.cat_apps),
                getString(R.string.hidden_summary, Prefs.hidden(this).size()),
                v -> openPage(PAGE_APPS)));
        launcher.add(makeTile(R.drawable.ic_setup, getText(R.string.setup_again_title),
                getText(R.string.setup_again_summary), v -> open(new Intent(this, SetupActivity.class))));
        launcher.add(makeTile(R.drawable.ic_schedule, getText(R.string.update_auto_title),
                getText(Updater.autoCheck(this) ? R.string.update_auto_on : R.string.update_auto_off), v -> {
                    boolean on = !Updater.autoCheck(this);
                    Updater.setAutoCheck(this, on);
                    if (on) Updater.checkIfDue(this, ok -> {
                        if (!isFinishing()) refresh();
                    });
                    refresh();
                }));
        addTileLines(launcher, gap);
    }

    /** Time in large type where the app name stands on other pages, the date under it. */
    private void showClock() {
        Date now = new Date();
        brand.setText(DateFormat.getTimeFormat(this).format(now));
        title.setText(DateFormat.format(
                DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"), now));
    }

    // -------------------------------------------------------------------- tiles

    /** Two tiles to a line, both as tall as the taller one; an odd last one keeps its width. */
    private void addTileLines(ArrayList<View> tiles, int gap) {
        for (int i = 0; i < tiles.size(); i += 2) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            line.addView(tiles.get(i), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            View second = i + 1 < tiles.size() ? tiles.get(i + 1) : new View(this);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
            secondParams.setMarginStart(gap);
            line.addView(second, secondParams);
            LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lineParams.bottomMargin = gap;
            rows.addView(line, lineParams);
        }
    }

    /** A tile for a system screen; left out when the TV does not have that screen. */
    private void addTile(ArrayList<View> tiles, int icon, int titleRes, CharSequence summary,
                         final Intent target) {
        if (target == null) return;
        tiles.add(makeTile(icon, getText(titleRes), summary, v -> open(target)));
    }

    /** A tile: icon, a name of up to two lines and an optional state line. */
    private View makeTile(int icon, CharSequence titleText, CharSequence summary,
                          View.OnClickListener onClick) {
        View tile = getLayoutInflater().inflate(R.layout.settings_tile, rows, false);
        ((ImageView) tile.findViewById(R.id.tile_icon)).setImageResource(icon);
        ((TextView) tile.findViewById(R.id.tile_title)).setText(titleText);
        TextView summaryView = tile.findViewById(R.id.tile_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);
        tile.setOnClickListener(onClick);
        focusables.add(tile);
        return tile;
    }

    /** The first of the intents this TV has a real screen for, or null. */
    private Intent real(Intent... intents) {
        for (Intent intent : intents) {
            Intent real = DefaultLauncher.real(this, intent);
            if (real != null) return real;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private CharSequence networkState() {
        NetworkInfo info = null;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null) info = cm.getActiveNetworkInfo();
        } catch (RuntimeException ignored) {
            // Treated as no network.
        }
        if (info == null || !info.isConnected()) return getText(R.string.net_none);
        return getText(info.getType() == ConnectivityManager.TYPE_ETHERNET
                ? R.string.qs_ethernet : R.string.qs_connected);
    }

    // ------------------------------------------------------------------ updates

    private CharSequence updateState() {
        int progress = Updater.progress();
        if (progress >= 0) return getString(R.string.update_downloading, progress);
        String available = Updater.available(this);
        if (available != null) return getString(R.string.update_available, available);
        return getText(Updater.lastChecked(this) > 0 ? R.string.update_current : R.string.update_unknown);
    }

    /** A known update is offered for installing; otherwise GitHub is asked now. */
    private void onUpdateClicked() {
        if (Updater.progress() >= 0) return; // already downloading
        String available = Updater.available(this);
        if (available != null) {
            offerUpdate(available);
            return;
        }
        if (updateSummary != null) updateSummary.setText(R.string.update_checking);
        Updater.check(this, ok -> {
            if (isFinishing()) return;
            refresh();
            String found = Updater.available(this);
            if (!ok) {
                Toast.makeText(this, R.string.update_check_failed, Toast.LENGTH_LONG).show();
            } else if (found != null) {
                offerUpdate(found);
            } else {
                Toast.makeText(this, R.string.update_latest_toast, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void offerUpdate(String version) {
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.update_dialog_title, version))
                .setMessage(R.string.update_dialog_text)
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_install, (dialog, which) -> {
                    if (Updater.canInstall(this)) {
                        startUpdate();
                    } else {
                        askInstallPermission();
                    }
                })
                .show();
    }

    /**
     * Android 8+ lets an app hand APKs to the installer only after the user allows it
     * on a system screen. The update goes on by itself once they come back.
     */
    private void askInstallPermission() {
        final Intent screen = DefaultLauncher.real(this, new Intent(
                "android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + getPackageName())));
        if (screen == null) {
            Toast.makeText(this, R.string.update_permission_manual, Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_text)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.update_permission_open, (dialog, which) -> {
                    installAfterPermission = true;
                    open(screen);
                })
                .show();
    }

    private void startUpdate() {
        Toast.makeText(this, R.string.update_started, Toast.LENGTH_SHORT).show();
        Updater.downloadAndInstall(this, new Updater.InstallListener() {
            @Override
            public void onProgress(int percent) {
                if (updateSummary != null) updateSummary.setText(getString(R.string.update_downloading, percent));
            }

            @Override
            public void onDone(String error) {
                // The system installer asks for confirmation on its own; only errors are told here.
                if (error != null) Toast.makeText(getApplicationContext(), error, Toast.LENGTH_LONG).show();
                if (!isFinishing()) refresh();
            }
        });
        refresh();
    }

    private void buildDefault() {
        title.setText(R.string.default_title);
        addRow(R.string.role_title,
                getText(DefaultLauncher.isDefault(this) ? R.string.role_on : R.string.role_off), v -> {
                    if (!DefaultLauncher.requestDefault(this, REQUEST_HOME_ROLE)) {
                        Toast.makeText(this, R.string.role_missing, Toast.LENGTH_LONG).show();
                    }
                });
        addRow(R.string.service_title,
                getText(DefaultLauncher.serviceEnabled(this) ? R.string.service_on : R.string.service_off),
                v -> DefaultLauncher.openServiceSettings(this));
    }

    private void buildWallpaper() {
        title.setText(R.string.cat_wallpaper);
        int mode = Prefs.wallpaperMode(this);
        addRow(R.string.source_title, getText(SOURCE_NAMES[indexOf(SOURCES, mode)]), v -> showSources());
        if (mode == Prefs.WALLPAPER_CLOUD) {
            String picture = Prefs.cloudPicture(this);
            addRow(R.string.theme_title, picture.isEmpty() ? Prefs.cloudTheme(this)
                    : Prefs.cloudTheme(this) + " · " + picture, v -> showThemes());
            addRow(R.string.refresh_title, getText(R.string.refresh_summary), v -> {
                Wallpapers.sync(this, true);
                Toast.makeText(this, R.string.refresh_started, Toast.LENGTH_SHORT).show();
            });
        } else if (mode == Prefs.WALLPAPER_OWN) {
            addRow(R.string.own_add_title, getString(R.string.own_count, Wallpapers.ownCount(this)),
                    v -> pickImages());
            addRow(R.string.own_clear_title, getText(R.string.own_clear_summary), v -> {
                Wallpapers.clearOwn(this);
                Toast.makeText(this, R.string.own_cleared, Toast.LENGTH_SHORT).show();
                // Deletion runs in the background; give it a moment before recounting.
                rows.postDelayed(this::refresh, 500);
            });
        }
        if (mode != Prefs.WALLPAPER_OFF) {
            int minutes = Prefs.WALLPAPER_INTERVALS[Prefs.wallpaperInterval(this)];
            addRow(R.string.interval_title, intervalName(minutes), v -> showIntervals());
            addRow(R.string.dim_title,
                    getResources().getStringArray(R.array.dim_levels)[Prefs.wallpaperDim(this)],
                    v -> showDimLevels());
        }
    }

    private CharSequence intervalName(int minutes) {
        return minutes == 0 ? getText(R.string.interval_on_return)
                : getString(R.string.interval_minutes, minutes);
    }

    private void showIntervals() {
        CharSequence[] names = new CharSequence[Prefs.WALLPAPER_INTERVALS.length];
        for (int i = 0; i < names.length; i++) names[i] = intervalName(Prefs.WALLPAPER_INTERVALS[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.interval_title)
                .setSingleChoiceItems(names, Prefs.wallpaperInterval(this), (dialog, which) -> {
                    dialog.dismiss();
                    Prefs.setWallpaperInterval(this, which);
                    refresh();
                })
                .show();
    }

    private void showDimLevels() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.dim_title)
                .setSingleChoiceItems(R.array.dim_levels, Prefs.wallpaperDim(this), (dialog, which) -> {
                    dialog.dismiss();
                    Prefs.setWallpaperDim(this, which);
                    refresh();
                })
                .show();
    }

    private void buildHome() {
        title.setText(R.string.cat_home);
        addRow(R.string.stats_title, statsState(), v -> showStats());
        addRow(R.string.nav_sound_title,
                getText(Prefs.navSound(this) ? R.string.nav_sound_on : R.string.nav_sound_off), v -> {
                    Prefs.setNavSound(this, !Prefs.navSound(this));
                    refresh();
                });
    }

    private void buildApps() {
        title.setText(R.string.cat_apps);
        addRow(R.string.recent_title,
                getText(Prefs.showRecent(this) ? R.string.recent_on : R.string.recent_off), v -> {
                    Prefs.setShowRecent(this, !Prefs.showRecent(this));
                    refresh();
                });
        addRow(R.string.hidden_title, getString(R.string.hidden_summary, Prefs.hidden(this).size()),
                v -> showHidden());
    }

    private CharSequence defaultState() {
        if (DefaultLauncher.isDefault(this)) return getText(R.string.default_system);
        if (DefaultLauncher.serviceEnabled(this)) return getText(R.string.default_service);
        return getText(R.string.default_none);
    }

    private CharSequence statsState() {
        int stats = Prefs.statsMask(this);
        if (stats == 0) return getText(R.string.stats_off);
        ArrayList<CharSequence> shown = new ArrayList<>(3);
        if ((stats & SystemStats.NET) != 0) shown.add(getText(R.string.stats_net_short));
        if ((stats & SystemStats.CPU) != 0) shown.add(getText(R.string.stats_cpu_short));
        if ((stats & SystemStats.RAM) != 0) shown.add(getText(R.string.stats_ram_short));
        return TextUtils.join(", ", shown);
    }

    private static int indexOf(int[] values, int value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) return i;
        }
        return 0;
    }

    // ----------------------------------------------------------- wallpaper source

    private void showSources() {
        CharSequence[] names = new CharSequence[SOURCES.length];
        for (int i = 0; i < names.length; i++) names[i] = getText(SOURCE_NAMES[i]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.source_title)
                .setSingleChoiceItems(names, indexOf(SOURCES, Prefs.wallpaperMode(this)), (dialog, which) -> {
                    dialog.dismiss();
                    Prefs.setWallpaperMode(this, SOURCES[which]);
                    Wallpapers.sync(this, false);
                    refresh();
                })
                .show();
    }

    // -------------------------------------------------------------- cloud themes

    /** Step one: which theme. The list comes from the repository each time. */
    private void showThemes() {
        Toast.makeText(this, R.string.theme_loading, Toast.LENGTH_SHORT).show();
        Wallpapers.listThemes(themes -> {
            if (isFinishing()) return;
            if (themes == null) {
                Toast.makeText(this, R.string.theme_failed, Toast.LENGTH_LONG).show();
                return;
            }
            if (themes.isEmpty()) {
                Toast.makeText(this, R.string.theme_empty, Toast.LENGTH_LONG).show();
                return;
            }
            final String[] names = themes.toArray(new String[0]);
            new AlertDialog.Builder(this)
                    .setTitle(R.string.theme_title)
                    .setSingleChoiceItems(names, themes.indexOf(Prefs.cloudTheme(this)), (dialog, which) -> {
                        dialog.dismiss();
                        showPictures(names[which]);
                    })
                    .show();
        });
    }

    /** Step two: the whole theme, or one picture of it. */
    private void showPictures(final String theme) {
        Wallpapers.listPictures(theme, pictures -> {
            if (isFinishing()) return;
            if (pictures == null) {
                Toast.makeText(this, R.string.theme_failed, Toast.LENGTH_LONG).show();
                return;
            }
            final String[] items = new String[pictures.size() + 1];
            items[0] = getString(R.string.theme_all, pictures.size());
            for (int i = 0; i < pictures.size(); i++) items[i + 1] = pictures.get(i);
            int checked = 0;
            if (theme.equals(Prefs.cloudTheme(this))) {
                checked = pictures.indexOf(Prefs.cloudPicture(this)) + 1;
            }
            new AlertDialog.Builder(this)
                    .setTitle(theme)
                    .setSingleChoiceItems(items, checked, (dialog, which) -> {
                        dialog.dismiss();
                        Prefs.setCloud(this, theme, which == 0 ? "" : items[which]);
                        Wallpapers.sync(this, true);
                        Toast.makeText(this, R.string.refresh_started, Toast.LENGTH_SHORT).show();
                        refresh();
                    })
                    .show();
        });
    }

    // ------------------------------------------------------------ own wallpapers

    /**
     * The system file picker hands over exactly the files the user chose and needs
     * no permission. Google TV has none, only a stub that shows an error; there the
     * launcher offers the pictures the media index knows about, USB drives included.
     */
    private void pickImages() {
        Intent[] pickers = {
                new Intent(Intent.ACTION_OPEN_DOCUMENT),
                new Intent(Intent.ACTION_GET_CONTENT)};
        for (Intent picker : pickers) {
            picker.addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("image/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            Intent real = DefaultLauncher.real(this, picker);
            if (real == null) continue;
            try {
                startActivityForResult(real, REQUEST_PICK_IMAGES);
                return;
            } catch (RuntimeException ignored) {
                // Try the next way.
            }
        }
        pickFromMediaStore();
    }

    /** The permissions that let the launcher list images; any one of them is enough. */
    private static String[] readImagesPermissions() {
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14 lets the user share only some photos; that is fine too.
            return new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"};
        }
        return new String[]{Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE};
    }

    private boolean canReadImages() {
        if (Build.VERSION.SDK_INT < 23) return true;
        for (String permission : readImagesPermissions()) {
            if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        }
        return false;
    }

    /** Asks for read access to pictures first if it is not there yet. */
    private void pickFromMediaStore() {
        if (Build.VERSION.SDK_INT >= 23 && !canReadImages()) {
            requestPermissions(readImagesPermissions(), REQUEST_READ_IMAGES);
            return;
        }
        loadMediaStore();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_READ_IMAGES) return;
        if (canReadImages()) {
            loadMediaStore();
        } else {
            // Without access the shared folder is still there.
            showSharedFolder(R.string.own_no_access);
        }
    }

    /** Picture names and addresses from the media index, newest first. */
    private void loadMediaStore() {
        final Uri collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Worker.io(() -> {
            final ArrayList<Uri> uris = new ArrayList<>();
            final ArrayList<String> names = new ArrayList<>();
            String[] columns = {MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME};
            try (Cursor c = getContentResolver().query(collection, columns, null, null,
                    MediaStore.Images.Media.DATE_MODIFIED + " DESC")) {
                while (c != null && c.moveToNext() && uris.size() < MAX_PICKER_ITEMS) {
                    uris.add(ContentUris.withAppendedId(collection, c.getLong(0)));
                    String name = c.getString(1);
                    String folder = c.getString(2);
                    names.add(folder == null ? String.valueOf(name) : name + "  ·  " + folder);
                }
            } catch (RuntimeException ignored) {
                // No media index on this device: same as finding nothing.
            }
            Worker.MAIN.post(() -> {
                if (isFinishing()) return;
                if (uris.isEmpty()) {
                    showSharedFolder(R.string.own_none_found);
                } else {
                    showMediaPicker(uris, names);
                }
            });
        });
    }

    private void showMediaPicker(final ArrayList<Uri> uris, ArrayList<String> names) {
        final boolean[] chosen = new boolean[uris.size()];
        new AlertDialog.Builder(this)
                .setTitle(R.string.own_pick_title)
                .setMultiChoiceItems(names.toArray(new CharSequence[0]), chosen,
                        (dialog, which, checked) -> chosen[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.own_pick_add, (dialog, which) -> {
                    ArrayList<Uri> picked = new ArrayList<>();
                    for (int i = 0; i < chosen.length; i++) {
                        if (chosen[i]) picked.add(uris.get(i));
                    }
                    importPictures(picked);
                })
                .show();
    }

    /** Last resort: point the user to the folder they can fill over USB, ADB or the network. */
    private void showSharedFolder(int reason) {
        File shared = Wallpapers.sharedDir(this);
        String message = getString(reason);
        if (shared != null) message += "\n\n" + shared.getPath();
        new AlertDialog.Builder(this)
                .setTitle(R.string.own_add_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;

        ArrayList<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri uri = clip.getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importPictures(uris);
    }

    private void importPictures(ArrayList<Uri> uris) {
        if (uris.isEmpty()) return;
        Wallpapers.importOwn(this, uris, count -> {
            if (isFinishing()) return;
            if (count > 0) {
                // Adding pictures is a clear sign the user wants to see them.
                Prefs.setWallpaperMode(this, Prefs.WALLPAPER_OWN);
                Toast.makeText(this, getString(R.string.own_added, count), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.own_failed, Toast.LENGTH_LONG).show();
            }
            refresh();
        });
    }

    // --------------------------------------------------------- system indicators

    private void showStats() {
        final int[] bits = {SystemStats.NET, SystemStats.CPU, SystemStats.RAM};
        CharSequence[] labels = {
                getText(R.string.stats_net), getText(R.string.stats_cpu), getText(R.string.stats_ram)};
        int mask = Prefs.statsMask(this);
        final boolean[] on = new boolean[bits.length];
        for (int i = 0; i < bits.length; i++) on[i] = (mask & bits[i]) != 0;
        new AlertDialog.Builder(this)
                .setTitle(R.string.stats_dialog_title)
                .setMultiChoiceItems(labels, on, (dialog, which, checked) -> on[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    int chosen = 0;
                    for (int i = 0; i < bits.length; i++) {
                        if (on[i]) chosen |= bits[i];
                    }
                    Prefs.setStatsMask(this, chosen);
                    refresh();
                })
                .show();
    }

    // --------------------------------------------------------------- hidden apps

    private void showHidden() {
        final ArrayList<String> packages = new ArrayList<>(Prefs.hidden(this));
        if (packages.isEmpty()) {
            Toast.makeText(this, R.string.hidden_empty, Toast.LENGTH_LONG).show();
            return;
        }
        final PackageManager pm = getPackageManager();
        final ArrayList<String[]> items = new ArrayList<>(packages.size()); // {label, package}
        for (String pkg : packages) {
            String label = pkg;
            try {
                label = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString();
            } catch (PackageManager.NameNotFoundException ignored) {
                // Uninstalled since it was hidden: show the package name.
            }
            items.add(new String[]{label, pkg});
        }
        final Collator collator = Collator.getInstance();
        Collections.sort(items, (a, b) -> collator.compare(a[0], b[0]));

        CharSequence[] labels = new CharSequence[items.size()];
        final boolean[] keep = new boolean[items.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = items.get(i)[0];
            keep[i] = true;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.hidden_dialog_title)
                .setMultiChoiceItems(labels, keep, (dialog, which, checked) -> keep[which] = checked)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    HashSet<String> still = new HashSet<>();
                    for (int i = 0; i < keep.length; i++) {
                        if (keep[i]) still.add(items.get(i)[1]);
                    }
                    Prefs.setHidden(this, still);
                    refresh();
                })
                .show();
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }
}
