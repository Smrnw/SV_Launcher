package com.smrnw.svlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * The home screen: wallpaper, clock, status line, a row of favorites on its own
 * panel and the grid with all other apps below it.
 */
public final class HomeActivity extends Activity implements
        AdapterView.OnItemClickListener,
        AdapterView.OnItemLongClickListener,
        AdapterView.OnItemSelectedListener,
        AbsListView.OnScrollListener,
        View.OnFocusChangeListener {

    private static final long WALLPAPER_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long STATS_INTERVAL_MS = 2000L;
    private static final float FOCUS_SCALE = 1.12f;
    private static final int FOCUS_ANIM_MS = 160;

    // Actions of the app menu (long press or the Menu key).
    private static final int ACTION_PIN = 0;
    private static final int ACTION_UNPIN = 1;
    private static final int ACTION_MOVE_LEFT = 2;
    private static final int ACTION_MOVE_RIGHT = 3;
    private static final int ACTION_HIDE = 4;
    private static final int ACTION_INFO = 5;

    /** Rendered cards, capped by count. Only cards that were actually shown get here. */
    private final LruCache<String, Bitmap> cards = new LruCache<>(64);
    private final HashSet<String> pending = new HashSet<>();
    private final AppAdapter adapter = new AppAdapter();

    /** Every launchable app, sorted by name. */
    private ArrayList<Apps.Entry> allApps = new ArrayList<>();
    /** Pinned apps as shown in the favorites row, in the user's order. */
    private ArrayList<Apps.Entry> favorites = new ArrayList<>();
    /** Everything else: the content of the grid. */
    private ArrayList<Apps.Entry> gridApps = new ArrayList<>();

    private ArrayList<String> pinned = new ArrayList<>();
    private HashSet<String> hidden = new HashSet<>();
    private int prefsRevision = -1;

    private WallpaperView wallpaper;
    private View favoritesPanel;
    private LinearLayout favoritesRow;
    private View favoritesEmpty;
    private GridView grid;
    private TextView statsView;
    private SystemStats stats;
    private int statsMask;
    private boolean navSound;
    /** The grid cell that currently wears the focus look, if any. */
    private View focusedCell;

    private int screenW;
    private int screenH;
    private int cardW;
    private int cardH;
    private int cardGap;
    private float focusZ;

    private boolean started;
    private boolean destroyed;
    private boolean wallpaperBusy;
    /** False until the focus has been put on the first app after the apps loaded. */
    private boolean focusPlaced;
    private String wallpaperPath;

    private final Runnable wallpaperTick = new Runnable() {
        @Override
        public void run() {
            loadWallpaper(false);
            // Also the retry loop for the cloud: if the TV came up without a network,
            // the sync is attempted again on every tick until it finally succeeds.
            // Once it has, this call is a cheap no-op for the next few hours.
            Wallpapers.sync(HomeActivity.this, false);
            Worker.MAIN.postDelayed(this, WALLPAPER_INTERVAL_MS);
        }
    };

    private final Runnable wallpapersChanged = () -> loadWallpaper(true);

    /** Refreshes the status line. Runs only while the home screen is visible. */
    private final Runnable statsTick = new Runnable() {
        @Override
        public void run() {
            final int mask = statsMask;
            Worker.bg(() -> {
                final String text = stats.sample(mask);
                Worker.MAIN.post(() -> {
                    if (started && statsMask == mask) statsView.setText(text);
                });
            });
            Worker.MAIN.postDelayed(this, STATS_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // An update may bring a new banner, so the rendered cards go as well.
            cards.evictAll();
            loadApps();
        }
    };

    /**
     * The grid clicks when the selection moves; the favorites row scrolls on its own
     * and would stay silent. It gets the same click, so both feel alike.
     */
    private final View.OnKeyListener favoriteKeys = (view, keyCode, event) -> {
        if (navSound && event.getAction() == KeyEvent.ACTION_DOWN) {
            int index = favoritesRow.indexOfChild(view);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && index > 0) {
                view.playSoundEffect(SoundEffectConstants.NAVIGATION_LEFT);
            } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    && index < favoritesRow.getChildCount() - 1) {
                view.playSoundEffect(SoundEffectConstants.NAVIGATION_RIGHT);
            }
        }
        return false; // the row still moves the focus itself
    };

    /** Focus look for the items of the favorites row; the grid has its own callbacks. */
    private final View.OnFocusChangeListener favoriteFocus =
            (view, hasFocus) -> setFocused(view, hasFocus, true);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.home);
        // The wallpaper view paints the whole screen itself. Without a window
        // background no pixel is drawn twice.
        getWindow().setBackgroundDrawable(null);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = Math.max(dm.widthPixels, dm.heightPixels);
        screenH = Math.min(dm.widthPixels, dm.heightPixels);
        int columns = getResources().getInteger(R.integer.grid_columns);
        int pad = getResources().getDimensionPixelSize(R.dimen.grid_pad);
        cardGap = getResources().getDimensionPixelSize(R.dimen.grid_gap);
        cardW = (screenW - 2 * pad - (columns - 1) * cardGap) / columns;
        cardH = cardW * 9 / 16;
        focusZ = getResources().getDimension(R.dimen.focus_z);

        wallpaper = findViewById(R.id.wallpaper);
        statsView = findViewById(R.id.stats);
        stats = new SystemStats(this);

        favoritesPanel = findViewById(R.id.favorites);
        favoritesRow = findViewById(R.id.favorites_row);
        favoritesEmpty = findViewById(R.id.favorites_empty);
        // The panel keeps its height when empty: one card plus its label.
        findViewById(R.id.favorites_scroll).setMinimumHeight(
                cardH + getResources().getDimensionPixelSize(R.dimen.favorites_label_space));
        // Cards sliding out sideways are cut along the rounded shape of the panel.
        favoritesPanel.setClipToOutline(true);

        grid = findViewById(R.id.grid);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(this);
        grid.setOnItemLongClickListener(this);
        grid.setOnItemSelectedListener(this);
        grid.setOnScrollListener(this);
        grid.setOnFocusChangeListener(this);
        grid.requestFocus();

        findViewById(R.id.settings).setOnClickListener(
                v -> startActivity(new Intent(this, SettingsActivity.class)));

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(packageReceiver, filter);
        }

        loadApps();

        // First start: walk the user through the few things that need their say.
        if (!Prefs.setupDone(this)) startActivity(new Intent(this, SetupActivity.class));
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        // Settings may have unhidden apps or switched the wallpaper source meanwhile.
        if (prefsRevision != Prefs.revision) {
            readPrefs();
            rebuild(null);
        }
        applyStats();
        Wallpapers.listener = wallpapersChanged;
        // A different wallpaper every time the home screen opens, then on a timer.
        loadWallpaper(false);
        Worker.MAIN.postDelayed(wallpaperTick, WALLPAPER_INTERVAL_MS);
        Wallpapers.sync(this, false);
    }

    /**
     * The settings panel is translucent: while it is open the home screen is paused
     * but not stopped, so onStart does not run when the panel closes. Whatever was
     * changed there is picked up here.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (prefsRevision == Prefs.revision) return;
        readPrefs();
        rebuild(null);
        applyStats();
        // Keeps the picture if it still belongs to the selected source.
        loadWallpaper(true);
        Wallpapers.sync(this, false);
    }

    /** Shows or hides the status line according to the settings and (re)starts it. */
    private void applyStats() {
        // Not about stats, but read at the same moments: whenever settings may have changed.
        navSound = Prefs.navSound(this);
        // Plays only if the system's own interface sounds are on, too.
        grid.setSoundEffectsEnabled(navSound);

        Worker.MAIN.removeCallbacks(statsTick);
        statsMask = Prefs.statsMask(this);
        statsView.setVisibility(statsMask != 0 ? View.VISIBLE : View.GONE);
        if (statsMask != 0) {
            // Counters went stale while we were away: start over.
            Worker.bg(stats::reset);
            Worker.MAIN.post(statsTick);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        started = false;
        Wallpapers.listener = null;
        Worker.MAIN.removeCallbacks(wallpaperTick);
        Worker.MAIN.removeCallbacks(statsTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        unregisterReceiver(packageReceiver);
        wallpaper.clear();
        cards.evictAll();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already at home: back to the first app.
        goToFirst();
    }

    @Override
    public void onBackPressed() {
        // The home screen is the bottom of the stack, Back has nowhere to go.
        goToFirst();
    }

    private void goToFirst() {
        if (favoritesRow.getChildCount() > 0) {
            favoritesRow.getChildAt(0).requestFocus();
        } else {
            grid.requestFocus();
            if (!gridApps.isEmpty()) grid.setSelection(0);
        }
    }

    /**
     * A launcher spends most of its life hidden behind some other app. The moment
     * the UI goes away the wallpaper, by far the largest allocation, is dropped and
     * decoded again on return. Under real memory pressure the cards go too.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            wallpaper.clear();
            wallpaperPath = null;
        }
        if (level >= TRIM_MEMORY_BACKGROUND
                || (level >= TRIM_MEMORY_RUNNING_LOW && level < TRIM_MEMORY_UI_HIDDEN)) {
            cards.evictAll();
        }
    }

    // ---------------------------------------------------------------- wallpaper

    /**
     * @param keepCurrent true to merely make sure something valid is on screen (used
     *                    while the repository is syncing), false to switch pictures.
     */
    private void loadWallpaper(final boolean keepCurrent) {
        if (wallpaperBusy || !started) return;
        wallpaperBusy = true;
        final Context app = getApplicationContext();
        final String current = wallpaper.isEmpty() ? null : wallpaperPath;
        Worker.bg(() -> {
            Bitmap bitmap = null;
            String path = current;
            boolean clear = false;
            if (!(keepCurrent && current != null && Wallpapers.isAvailable(app, current))) {
                String next = Wallpapers.pick(app, current);
                if (next == null) {
                    clear = true;
                } else if (!next.equals(current)) {
                    bitmap = Wallpapers.decode(app, next, screenW, screenH);
                    path = next;
                }
            }
            final Bitmap fBitmap = bitmap;
            final String fPath = path;
            final boolean fClear = clear;
            Worker.MAIN.post(() -> {
                wallpaperBusy = false;
                if (destroyed || !started) {
                    if (fBitmap != null) fBitmap.recycle();
                    return;
                }
                if (fClear) {
                    wallpaper.clear();
                    wallpaperPath = null;
                } else if (fBitmap != null) {
                    wallpaper.show(fBitmap);
                    wallpaperPath = fPath;
                }
            });
        });
    }

    // --------------------------------------------------------------------- apps

    private void loadApps() {
        final Context app = getApplicationContext();
        Worker.bg(() -> {
            final ArrayList<Apps.Entry> list = Apps.load(app);
            Worker.MAIN.post(() -> {
                if (destroyed) return;
                allApps = list;
                if (prefsRevision != Prefs.revision) readPrefs();
                rebuild(null);
            });
        });
    }

    private void readPrefs() {
        pinned = Prefs.pinned(this);
        hidden = Prefs.hidden(this);
        prefsRevision = Prefs.revision;
    }

    /** Saves the lists without making onStart think somebody else changed them. */
    private void savePrefs() {
        Prefs.setPinned(this, pinned);
        Prefs.setHidden(this, hidden);
        prefsRevision = Prefs.revision;
    }

    /**
     * Splits the apps into the favorites row and the grid and refreshes both.
     *
     * @param focusPkg package to move the focus to afterwards, or null to leave it
     *                 where it is
     */
    private void rebuild(String focusPkg) {
        // The row is about to be rebuilt from scratch. If the focus is inside it,
        // carry it over to the same app.
        if (focusPkg == null && favoritesRow.hasFocus()) {
            Apps.Entry focused = entryOf(getCurrentFocus());
            if (focused != null) focusPkg = focused.pkg;
        }

        // Pinned apps keep the user's order. A package that is missing right now
        // (being updated, on an unplugged drive) stays in the saved list.
        ArrayList<Apps.Entry> pinnedApps = new ArrayList<>();
        HashSet<String> shown = new HashSet<>();
        for (int i = 0; i < pinned.size(); i++) {
            String pkg = pinned.get(i);
            if (hidden.contains(pkg)) continue;
            for (int j = 0, n = allApps.size(); j < n; j++) {
                Apps.Entry e = allApps.get(j);
                if (e.pkg.equals(pkg)) {
                    if (shown.add(pkg)) pinnedApps.add(e);
                    break;
                }
            }
        }
        ArrayList<Apps.Entry> rest = new ArrayList<>(allApps.size());
        for (int i = 0, n = allApps.size(); i < n; i++) {
            Apps.Entry e = allApps.get(i);
            if (!hidden.contains(e.pkg) && !shown.contains(e.pkg)) rest.add(e);
        }

        favorites = pinnedApps;
        gridApps = rest;
        buildFavorites();
        adapter.notifyDataSetChanged();

        if (focusPkg != null) {
            focusOn(focusPkg);
        } else if (!focusPlaced && !allApps.isEmpty()) {
            // While the list was still loading the only focusable thing was the gear,
            // so the focus went there. Start on the first app instead.
            goToFirst();
        }
        if (!allApps.isEmpty()) focusPlaced = true;
        // Whatever happened to the views, the remote must never be left without focus.
        if (getCurrentFocus() == null) grid.requestFocus();
    }

    /** Recreates the favorites row. It holds a handful of views, so no recycling. */
    private void buildFavorites() {
        favoritesRow.removeAllViews();
        // The panel always stays in place; with nothing pinned it explains how to fill it.
        favoritesEmpty.setVisibility(favorites.isEmpty() ? View.VISIBLE : View.GONE);

        LayoutInflater inflater = getLayoutInflater();
        for (int i = 0, n = favorites.size(); i < n; i++) {
            final Apps.Entry entry = favorites.get(i);
            View item = inflater.inflate(R.layout.item_app, favoritesRow, false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < n - 1) params.setMarginEnd(cardGap);

            Holder h = new Holder(item);
            h.entry = entry;
            item.setTag(h);
            h.label.setText(entry.label);
            Bitmap bitmap = cards.get(entry.pkg);
            h.card.setImageBitmap(bitmap);
            if (bitmap == null) requestCard(entry);

            item.setId(View.generateViewId());
            item.setFocusable(true);
            item.setOnFocusChangeListener(favoriteFocus);
            item.setOnKeyListener(favoriteKeys);
            item.setOnClickListener(v -> open(entry.launchIntent()));
            item.setOnLongClickListener(v -> {
                showMenu(entry);
                return true;
            });
            item.setNextFocusDownId(R.id.grid);
            // Left and right stay inside the row instead of wandering off to
            // whatever else happens to be in that direction.
            if (i == 0) item.setNextFocusLeftId(item.getId());
            if (i == n - 1) item.setNextFocusRightId(item.getId());
            favoritesRow.addView(item, params);
        }
    }

    private void focusOn(String pkg) {
        for (int i = 0, n = favorites.size(); i < n; i++) {
            if (favorites.get(i).pkg.equals(pkg)) {
                favoritesRow.getChildAt(i).requestFocus();
                return;
            }
        }
        for (int i = 0, n = gridApps.size(); i < n; i++) {
            if (gridApps.get(i).pkg.equals(pkg)) {
                grid.requestFocus();
                grid.setSelection(i);
                return;
            }
        }
    }

    /** The app behind a grid cell or a favorites item, or null for any other view. */
    private static Apps.Entry entryOf(View view) {
        Object tag = view != null ? view.getTag() : null;
        return tag instanceof Holder ? ((Holder) tag).entry : null;
    }

    private Apps.Entry gridEntryAt(int position) {
        return position >= 0 && position < gridApps.size() ? gridApps.get(position) : null;
    }

    private void requestCard(final Apps.Entry entry) {
        if (!pending.add(entry.pkg)) return;
        final Context app = getApplicationContext();
        Worker.bg(() -> {
            final Bitmap bitmap = Apps.card(app, entry, cardW, cardH);
            Worker.MAIN.post(() -> {
                pending.remove(entry.pkg);
                if (destroyed || bitmap == null) return;
                cards.put(entry.pkg, bitmap);
                if (!showCard(favoritesRow, entry, bitmap)) showCard(grid, entry, bitmap);
            });
        });
    }

    /** Puts a freshly rendered card into the view that is waiting for it. */
    private static boolean showCard(ViewGroup parent, Apps.Entry entry, Bitmap bitmap) {
        for (int i = 0, n = parent.getChildCount(); i < n; i++) {
            Holder h = (Holder) parent.getChildAt(i).getTag();
            if (h != null && h.entry == entry) {
                h.card.setImageBitmap(bitmap);
                return true;
            }
        }
        return false;
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (RuntimeException e) {
            // The app may have been removed or disabled a moment ago.
            Toast.makeText(this, R.string.cant_open, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Apps.Entry entry = gridEntryAt(position);
        if (entry != null) open(entry.launchIntent());
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        Apps.Entry entry = gridEntryAt(position);
        if (entry != null) showMenu(entry);
        return true;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            Apps.Entry entry = grid.hasFocus()
                    ? gridEntryAt(grid.getSelectedItemPosition())
                    : entryOf(getCurrentFocus());
            if (entry != null) {
                showMenu(entry);
                return true;
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    // ----------------------------------------------------------------- app menu

    private void showMenu(final Apps.Entry entry) {
        final int index = favorites.indexOf(entry);
        ArrayList<CharSequence> titles = new ArrayList<>(5);
        final ArrayList<Integer> actions = new ArrayList<>(5);
        if (index < 0) {
            titles.add(getText(R.string.menu_pin));
            actions.add(ACTION_PIN);
        } else {
            if (index > 0) {
                titles.add(getText(R.string.menu_move_left));
                actions.add(ACTION_MOVE_LEFT);
            }
            if (index < favorites.size() - 1) {
                titles.add(getText(R.string.menu_move_right));
                actions.add(ACTION_MOVE_RIGHT);
            }
            titles.add(getText(R.string.menu_unpin));
            actions.add(ACTION_UNPIN);
        }
        titles.add(getText(R.string.menu_hide));
        actions.add(ACTION_HIDE);
        titles.add(getText(R.string.menu_info));
        actions.add(ACTION_INFO);

        new AlertDialog.Builder(this)
                .setTitle(entry.label)
                .setItems(titles.toArray(new CharSequence[0]),
                        (dialog, which) -> onMenuAction(entry, actions.get(which)))
                .show();
    }

    private void onMenuAction(Apps.Entry entry, int action) {
        switch (action) {
            case ACTION_PIN:
                pinned.remove(entry.pkg);
                pinned.add(entry.pkg);
                break;
            case ACTION_UNPIN:
                pinned.remove(entry.pkg);
                break;
            case ACTION_MOVE_LEFT:
            case ACTION_MOVE_RIGHT: {
                // Swap with the neighbour as it appears on screen. The saved list may
                // also hold packages that are not installed at the moment.
                int index = favorites.indexOf(entry);
                int other = index + (action == ACTION_MOVE_LEFT ? -1 : 1);
                if (index < 0 || other < 0 || other >= favorites.size()) return;
                int a = pinned.indexOf(entry.pkg);
                int b = pinned.indexOf(favorites.get(other).pkg);
                if (a < 0 || b < 0) return;
                pinned.set(a, pinned.get(b));
                pinned.set(b, entry.pkg);
                break;
            }
            case ACTION_HIDE:
                hidden.add(entry.pkg);
                pinned.remove(entry.pkg);
                Toast.makeText(this, R.string.hidden_hint, Toast.LENGTH_LONG).show();
                break;
            case ACTION_INFO:
                open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", entry.pkg, null)));
                return;
            default:
                return;
        }
        savePrefs();
        rebuild(action == ACTION_HIDE ? null : entry.pkg);
    }

    // -------------------------------------------------------------------- focus

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (focusedCell != null && focusedCell != view) setFocused(focusedCell, false, true);
        focusedCell = null;
        if (view != null && grid.hasFocus()) {
            setFocused(view, true, true);
            focusedCell = view;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {
        if (focusedCell != null) setFocused(focusedCell, false, true);
        focusedCell = null;
    }

    /** Focus entering or leaving the grid as a whole. */
    @Override
    public void onFocusChange(View v, boolean hasFocus) {
        if (hasFocus) {
            View selected = grid.getSelectedView();
            if (selected != null) {
                setFocused(selected, true, true);
                focusedCell = selected;
            }
        } else if (focusedCell != null) {
            setFocused(focusedCell, false, true);
            focusedCell = null;
        }
    }

    /** The focused card grows, rises above its neighbours and shows its name. */
    private void setFocused(View item, boolean on, boolean animate) {
        Holder h = (Holder) item.getTag();
        if (h == null) return;
        if (on) {
            // Focus callbacks of the grid and of the favorites row can come in any
            // order (and grid cells get recycled), so a stale highlight may linger
            // somewhere. Whatever else is lit goes out before this card lights up.
            dimOthers(grid, item, animate);
            dimOthers(favoritesRow, item, animate);
        }
        h.lit = on;
        float scale = on ? FOCUS_SCALE : 1f;
        float z = on ? focusZ : 0f;
        float alpha = on ? 1f : 0f;
        // Affects drawing order only: the grown card must cover its neighbours.
        item.setTranslationZ(on ? 1f : 0f);
        h.card.animate().cancel();
        h.label.animate().cancel();
        if (animate) {
            h.card.animate().scaleX(scale).scaleY(scale).translationZ(z).setDuration(FOCUS_ANIM_MS);
            h.label.animate().alpha(alpha).setDuration(FOCUS_ANIM_MS);
        } else {
            h.card.setScaleX(scale);
            h.card.setScaleY(scale);
            h.card.setTranslationZ(z);
            h.label.setAlpha(alpha);
        }
    }

    private void dimOthers(ViewGroup parent, View keep, boolean animate) {
        for (int i = 0, n = parent.getChildCount(); i < n; i++) {
            View child = parent.getChildAt(i);
            Object tag = child.getTag();
            if (child != keep && tag instanceof Holder && ((Holder) tag).lit) {
                setFocused(child, false, animate);
            }
        }
    }

    /**
     * Rows leaving through the top of the grid are cut by its edge. Dimming them in
     * proportion to how much is cut turns the hard edge into a soft one, and costs
     * nothing: card alpha is handled by the GPU without an extra layer.
     */
    @Override
    public void onScroll(AbsListView view, int first, int visible, int total) {
        int top = view.getPaddingTop();
        for (int i = 0, n = view.getChildCount(); i < n; i++) {
            View child = view.getChildAt(i);
            Holder h = (Holder) child.getTag();
            if (h == null) continue;
            int cut = top - child.getTop();
            h.card.setAlpha(cut <= 0 ? 1f : Math.max(0f, 1f - cut / (float) cardH));
        }
    }

    @Override
    public void onScrollStateChanged(AbsListView view, int scrollState) {
    }

    private static final class Holder {
        final BannerView card;
        final TextView label;
        Apps.Entry entry;
        /** Wears the focus look right now. */
        boolean lit;

        Holder(View item) {
            card = item.findViewById(R.id.card);
            label = item.findViewById(R.id.label);
        }
    }

    private final class AppAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return gridApps.size();
        }

        @Override
        public Object getItem(int position) {
            return gridApps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            Holder h;
            if (view == null) {
                view = LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_app, parent, false);
                h = new Holder(view);
                view.setTag(h);
            } else {
                h = (Holder) view.getTag();
            }

            Apps.Entry entry = gridApps.get(position);
            h.entry = entry;
            h.label.setText(entry.label);
            h.card.setAlpha(1f);
            Bitmap bitmap = cards.get(entry.pkg);
            h.card.setImageBitmap(bitmap);
            if (bitmap == null) requestCard(entry);

            boolean selected = grid.hasFocus() && position == grid.getSelectedItemPosition();
            setFocused(view, selected, false);
            if (selected) {
                focusedCell = view;
            } else if (focusedCell == view) {
                focusedCell = null;
            }
            return view;
        }
    }
}
