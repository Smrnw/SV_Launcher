package com.smrnw.svlauncher;

import android.app.ActivityManager;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Locale;

/**
 * The status line at the top of the home screen: network, CPU, memory.
 *
 * Everything is read on demand from sources that need no special access. Speed and
 * CPU load are differences between two consecutive samples, so the very first
 * sample only primes the counters. Call {@link #sample} from one thread only.
 */
final class SystemStats {

    static final int NET = 1;
    static final int CPU = 2;
    static final int RAM = 4;
    static final int ALL = NET | CPU | RAM;

    private static final String SEPARATOR = "   ·   ";
    private static final String CPU_DIR = "/sys/devices/system/cpu/cpu";

    private final Context app;
    private final ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
    private final StringBuilder line = new StringBuilder(96);

    private long lastTime;
    private long lastRx;
    private long lastTx;
    private long lastCpuTotal;
    private long lastCpuIdle;
    /** /proc/stat is closed to apps on most Android 8+ firmwares; checked once. */
    private boolean procStatReadable = true;

    SystemStats(Context context) {
        app = context.getApplicationContext();
    }

    /** Forget the previous sample, e.g. after the home screen was hidden for a while. */
    void reset() {
        lastTime = 0;
    }

    /** Builds the status line for the indicators selected in {@code mask}. */
    String sample(int mask) {
        long now = SystemClock.elapsedRealtime();
        boolean primed = lastTime != 0 && now > lastTime;
        float seconds = primed ? (now - lastTime) / 1000f : 0f;
        line.setLength(0);

        if ((mask & NET) != 0) appendNetwork(primed, seconds);
        if ((mask & CPU) != 0) appendCpu(primed);
        if ((mask & RAM) != 0) appendMemory();

        lastTime = now;
        return line.toString();
    }

    private void separator() {
        if (line.length() > 0) line.append(SEPARATOR);
    }

    // ------------------------------------------------------------------ network

    private void appendNetwork(boolean primed, float seconds) {
        separator();
        NetworkInfo info = null;
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) info = cm.getActiveNetworkInfo();
        } catch (RuntimeException ignored) {
            // Treat a misbehaving connectivity service as "offline".
        }
        if (info == null || !info.isConnected()) {
            line.append(app.getString(R.string.net_none));
            lastRx = lastTx = -1;
            return;
        }
        switch (info.getType()) {
            case ConnectivityManager.TYPE_WIFI:
                line.append("Wi-Fi");
                break;
            case ConnectivityManager.TYPE_ETHERNET:
                line.append("Ethernet");
                break;
            default:
                line.append(app.getString(R.string.net_other));
                break;
        }

        // Device-wide byte counters; the speed is how fast they grow.
        long rx = TrafficStats.getTotalRxBytes();
        long tx = TrafficStats.getTotalTxBytes();
        if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) return;
        if (primed && lastRx >= 0 && rx >= lastRx && tx >= lastTx) {
            line.append("  ↓ ");
            appendSpeed((rx - lastRx) / seconds);
            line.append("  ↑ ");
            appendSpeed((tx - lastTx) / seconds);
        }
        lastRx = rx;
        lastTx = tx;
    }

    private void appendSpeed(float bytesPerSecond) {
        float kbit = bytesPerSecond * 8f / 1000f;
        if (kbit >= 1000f) {
            line.append(app.getString(R.string.unit_mbps,
                    String.format(Locale.getDefault(), "%.1f", kbit / 1000f)));
        } else {
            line.append(app.getString(R.string.unit_kbps, Integer.toString(Math.round(kbit))));
        }
    }

    // ---------------------------------------------------------------------- cpu

    private void appendCpu(boolean primed) {
        if (procStatReadable) {
            int load = loadFromProcStat(primed);
            if (load >= 0) {
                separator();
                line.append(app.getString(R.string.stat_cpu, load));
                return;
            }
            if (procStatReadable) return; // readable, just no previous sample yet
        }
        // Fallback: how far the cores are clocked up between their lowest and highest
        // frequency. Not the same thing as load, hence the "~" in the label.
        int estimate = loadFromFrequencies();
        if (estimate >= 0) {
            separator();
            line.append(app.getString(R.string.stat_cpu_estimate, estimate));
        }
    }

    /** Exact load since the previous sample, or -1 if unknown. */
    private int loadFromProcStat(boolean primed) {
        String first = readLine("/proc/stat");
        if (first == null || !first.startsWith("cpu ")) {
            procStatReadable = false;
            return -1;
        }
        // cpu  user nice system idle iowait irq softirq steal ...
        String[] parts = first.trim().split("\\s+");
        long total = 0;
        long idle = 0;
        try {
            for (int i = 1; i < parts.length && i <= 8; i++) {
                long value = Long.parseLong(parts[i]);
                total += value;
                if (i == 4 || i == 5) idle += value;
            }
        } catch (NumberFormatException e) {
            procStatReadable = false;
            return -1;
        }
        long dTotal = total - lastCpuTotal;
        long dIdle = idle - lastCpuIdle;
        lastCpuTotal = total;
        lastCpuIdle = idle;
        if (!primed || dTotal <= 0) return -1;
        return clampPercent(100f * (dTotal - dIdle) / dTotal);
    }

    /** Average position of the cores between min and max frequency, or -1 if unknown. */
    private int loadFromFrequencies() {
        int cores = Runtime.getRuntime().availableProcessors();
        float sum = 0f;
        int counted = 0;
        for (int i = 0; i < cores; i++) {
            long min = readLong(CPU_DIR + i + "/cpufreq/cpuinfo_min_freq");
            long max = readLong(CPU_DIR + i + "/cpufreq/cpuinfo_max_freq");
            if (min < 0 || max <= min) continue;
            // A core that is switched off has no current frequency: it counts as idle.
            long cur = readLong(CPU_DIR + i + "/cpufreq/scaling_cur_freq");
            sum += cur < 0 ? 0f : (cur - min) / (float) (max - min);
            counted++;
        }
        return counted == 0 ? -1 : clampPercent(100f * sum / counted);
    }

    private static int clampPercent(float value) {
        return Math.max(0, Math.min(100, Math.round(value)));
    }

    // ------------------------------------------------------------------- memory

    private void appendMemory() {
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return;
        am.getMemoryInfo(memory);
        if (memory.totalMem <= 0) return;
        separator();
        float gb = 1024f * 1024f * 1024f;
        line.append(app.getString(R.string.stat_ram,
                String.format(Locale.getDefault(), "%.1f", (memory.totalMem - memory.availMem) / gb),
                String.format(Locale.getDefault(), "%.1f", memory.totalMem / gb)));
    }

    // ------------------------------------------------------------------ helpers

    private static String readLine(String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path), 256)) {
            return reader.readLine();
        } catch (Exception e) {
            return null; // no such file or no access
        }
    }

    private static long readLong(String path) {
        String s = readLine(path);
        if (s == null) return -1;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
