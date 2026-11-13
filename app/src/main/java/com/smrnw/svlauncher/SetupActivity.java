package com.smrnw.svlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

/**
 * First-start wizard. Walks through the few decisions that are the user's to make:
 * becoming the default launcher (which needs a permission only they can grant),
 * where wallpapers come from (the cloud means network traffic) and what the status
 * line shows. Every step can be skipped, and the wizard can be run again from the
 * launcher settings.
 */
public final class SetupActivity extends Activity {

    private static final int STEP_WELCOME = 0;
    private static final int STEP_DEFAULT = 1;
    private static final int STEP_WALLPAPER = 2;
    private static final int STEP_STATS = 3;
    private static final int STEP_DONE = 4;

    private static final String STATE_STEP = "step";
    private static final int REQUEST_HOME_ROLE = 1;

    private TextView stepView;
    private TextView titleView;
    private TextView textView;
    private ViewGroup options;
    private TextView backButton;
    private TextView nextButton;
    private int step;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.setup);
        stepView = findViewById(R.id.setup_step);
        titleView = findViewById(R.id.setup_title);
        textView = findViewById(R.id.setup_text);
        options = findViewById(R.id.setup_options);
        backButton = findViewById(R.id.setup_back);
        nextButton = findViewById(R.id.setup_next);

        backButton.setOnClickListener(v -> go(step - 1));
        nextButton.setOnClickListener(v -> {
            if (step == STEP_DONE) {
                Prefs.setSetupDone(this);
                finish();
            } else {
                go(step + 1);
            }
        });
        if (savedInstanceState != null) step = savedInstanceState.getInt(STATE_STEP, STEP_WELCOME);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Also picks up what changed in system settings while we were away.
        show(-1);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, step);
    }

    @Override
    public void onBackPressed() {
        // Back walks the steps; the wizard itself is left through its last button.
        if (step > STEP_WELCOME) go(step - 1);
    }

    private void go(int newStep) {
        step = Math.max(STEP_WELCOME, Math.min(STEP_DONE, newStep));
        show(-1);
    }

    /**
     * Fills the screen for the current step.
     *
     * @param focusOption index of the option to focus, or -1 to focus "Next", so
     *                    that pressing OK again and again accepts the defaults
     */
    private void show(int focusOption) {
        options.removeAllViews();
        stepView.setText(getString(R.string.setup_step, step + 1, STEP_DONE + 1));
        backButton.setVisibility(step > STEP_WELCOME ? View.VISIBLE : View.INVISIBLE);
        nextButton.setText(step == STEP_DONE ? R.string.setup_finish : R.string.setup_next);

        switch (step) {
            case STEP_WELCOME:
                titleView.setText(R.string.setup_welcome_title);
                textView.setText(R.string.setup_welcome_text);
                break;
            case STEP_DEFAULT:
                showDefaultStep();
                break;
            case STEP_WALLPAPER:
                showWallpaperStep();
                break;
            case STEP_STATS:
                showStatsStep();
                break;
            default:
                titleView.setText(R.string.setup_done_title);
                textView.setText(R.string.setup_done_text);
                break;
        }

        View focus = focusOption >= 0 && focusOption < options.getChildCount()
                ? options.getChildAt(focusOption) : nextButton;
        focus.requestFocus();
    }

    private void showDefaultStep() {
        titleView.setText(R.string.setup_default_title);
        textView.setText(R.string.setup_default_text);
        addOption(getString(R.string.role_title),
                getString(DefaultLauncher.isDefault(this) ? R.string.role_on : R.string.role_off), v -> {
                    if (!DefaultLauncher.requestDefault(this, REQUEST_HOME_ROLE)) {
                        Toast.makeText(this, R.string.role_missing, Toast.LENGTH_LONG).show();
                    }
                });
        addOption(getString(R.string.service_title),
                getString(DefaultLauncher.serviceEnabled(this) ? R.string.service_on : R.string.service_off),
                v -> DefaultLauncher.openServiceSettings(this));
    }

    private void showWallpaperStep() {
        titleView.setText(R.string.setup_wallpaper_title);
        textView.setText(R.string.setup_wallpaper_text);
        int mode = Prefs.wallpaperMode(this);
        addChoice(R.string.setup_wallpaper_builtin, R.string.setup_wallpaper_builtin_summary,
                mode == Prefs.WALLPAPER_BUILTIN, 0, () -> Prefs.setWallpaperMode(this, Prefs.WALLPAPER_BUILTIN));
        addChoice(R.string.setup_wallpaper_cloud, R.string.setup_wallpaper_cloud_summary,
                mode == Prefs.WALLPAPER_CLOUD, 1, () -> Prefs.setWallpaperMode(this, Prefs.WALLPAPER_CLOUD));
        addChoice(R.string.setup_wallpaper_own, R.string.setup_wallpaper_own_summary,
                mode == Prefs.WALLPAPER_OWN, 2, () -> Prefs.setWallpaperMode(this, Prefs.WALLPAPER_OWN));
        addChoice(R.string.setup_wallpaper_off, R.string.setup_wallpaper_off_summary,
                mode == Prefs.WALLPAPER_OFF, 3, () -> Prefs.setWallpaperMode(this, Prefs.WALLPAPER_OFF));
    }

    private void showStatsStep() {
        titleView.setText(R.string.setup_stats_title);
        textView.setText(R.string.setup_stats_text);
        final int mask = Prefs.statsMask(this);
        int[] bits = {SystemStats.NET, SystemStats.CPU, SystemStats.RAM};
        int[] names = {R.string.stats_net, R.string.stats_cpu, R.string.stats_ram};
        for (int i = 0; i < bits.length; i++) {
            final int bit = bits[i];
            boolean on = (mask & bit) != 0;
            addChoice(names[i], on ? R.string.setup_shown : R.string.setup_not_shown, on, i,
                    () -> Prefs.setStatsMask(this, mask ^ bit));
        }
    }

    /** An option with a mark showing whether it is selected; choosing it redraws the step. */
    private void addChoice(int title, int summary, boolean selected, final int index,
                           final Runnable onChosen) {
        String mark = getString(selected ? R.string.setup_mark_on : R.string.setup_mark_off);
        addOption(mark + "  " + getString(title), getString(summary), v -> {
            onChosen.run();
            show(index);
        });
    }

    private void addOption(String title, String summary, View.OnClickListener onClick) {
        View row = getLayoutInflater().inflate(R.layout.setup_option, options, false);
        ((TextView) row.findViewById(R.id.option_title)).setText(title);
        ((TextView) row.findViewById(R.id.option_summary)).setText(summary);
        row.setOnClickListener(onClick);
        options.addView(row);
    }
}
