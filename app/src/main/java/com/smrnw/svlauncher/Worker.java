package com.smrnw.svlauncher;

import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Two single background threads (local work and network). Both threads exit after
 * a few seconds of inactivity, so an idle launcher keeps no extra threads alive.
 */
final class Worker {

    static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final ThreadPoolExecutor BG = pool("sv-bg");
    private static final ThreadPoolExecutor IO = pool("sv-io");

    private Worker() {
    }

    /** Short local work: querying apps, rendering cards, decoding wallpapers. */
    static void bg(Runnable r) {
        BG.execute(r);
    }

    /** Network work that may take a long time. */
    static void io(Runnable r) {
        IO.execute(r);
    }

    private static ThreadPoolExecutor pool(final String name) {
        ThreadPoolExecutor p = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(), r -> new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    r.run();
                }, name));
        p.allowCoreThreadTimeOut(true);
        return p;
    }
}
