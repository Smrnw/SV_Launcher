package com.smrnw.svlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;

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

    private ViewGroup rows;
    private TextView title;
    private int page = PAGE_MAIN;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // A narrow window docked to the right edge, full height.
        getWindow().setLayout(getResources().getDimensionPixelSize(R.dimen.side_panel_width),
                ViewGroup.LayoutParams.MATCH_PARENT);
        getWindow().setGravity(Gravity.END);
        setContentView(R.layout.settings);
        rows = findViewById(R.id.rows);
        title = findViewById(R.id.settings_title);
        if (savedInstanceState != null) page = savedInstanceState.getInt(STATE_PAGE, PAGE_MAIN);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also picks up what changed in system screens opened from here.
        show(page, focusedRow());
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
            show(PAGE_MAIN, page - 1);
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
        rows.removeViews(1, rows.getChildCount() - 1); // index 0 is the title
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
        int count = rows.getChildCount() - 1;
        if (count > 0) {
            View row = rows.getChildAt(1 + Math.max(0, Math.min(focus, count - 1)));
            if (row.isFocusable()) row.requestFocus();
        }
    }

    private int focusedRow() {
        View focused = getCurrentFocus();
        int index = focused != null ? rows.indexOfChild(focused) : -1;
        return index > 0 ? index - 1 : 0;
    }

    private void refresh() {
        show(page, focusedRow());
    }

    /** Adds a row: a title and a one-line state under it. */
    private void addRow(int titleRes, CharSequence summary, View.OnClickListener onClick) {
        addRow(0, titleRes, summary, false, onClick);
    }

    /**
     * Adds a row with an icon (0 for none); {@code opensPage} adds the arrow that
     * marks rows leading to a page of their own.
     */
    private void addRow(int icon, int titleRes, CharSequence summary, boolean opensPage,
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
    }

    private void buildMain() {
        title.setText(R.string.settings_page);
        addRow(R.drawable.ic_home, R.string.default_title, defaultState(), true,
                v -> show(PAGE_DEFAULT, 0));
        addRow(R.drawable.ic_wallpaper, R.string.cat_wallpaper,
                getText(SOURCE_NAMES[indexOf(SOURCES, Prefs.wallpaperMode(this))]), true,
                v -> show(PAGE_WALLPAPER, 0));
        addRow(R.drawable.ic_tv, R.string.cat_home, statsState(), true, v -> show(PAGE_HOME, 0));
        addRow(R.drawable.ic_apps, R.string.cat_apps,
                getString(R.string.hidden_summary, Prefs.hidden(this).size()), true,
                v -> show(PAGE_APPS, 0));
        addRow(R.drawable.ic_setup, R.string.setup_again_title, getText(R.string.setup_again_summary),
                false, v -> open(new Intent(this, SetupActivity.class)));
        addRow(R.drawable.ic_settings, R.string.system_title, getText(R.string.system_summary),
                false, v -> open(new Intent(Settings.ACTION_SETTINGS)));

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Cannot happen for our own package.
        }
        TextView versionView = (TextView) getLayoutInflater()
                .inflate(R.layout.settings_version, rows, false);
        versionView.setText(getString(R.string.version, version));
        rows.addView(versionView);
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

    private static String readImagesPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_IMAGES : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** Asks for read access to pictures first if it is not there yet. */
    private void pickFromMediaStore() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(readImagesPermission()) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{readImagesPermission()}, REQUEST_READ_IMAGES);
            return;
        }
        loadMediaStore();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_READ_IMAGES) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
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
