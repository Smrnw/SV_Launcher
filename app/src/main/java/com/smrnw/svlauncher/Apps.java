package com.smrnw.svlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Installed apps: the list itself and rendering of their cards. */
final class Apps {

    /** One launchable app. Holds strings only, no drawables. */
    static final class Entry {
        final String pkg;
        final String cls;
        final String label;
        final boolean leanback;

        Entry(String pkg, String cls, String label, boolean leanback) {
            this.pkg = pkg;
            this.cls = cls;
            this.label = label;
            this.leanback = leanback;
        }

        Intent launchIntent() {
            return new Intent(Intent.ACTION_MAIN)
                    .addCategory(leanback ? Intent.CATEGORY_LEANBACK_LAUNCHER : Intent.CATEGORY_LAUNCHER)
                    .setClassName(pkg, cls)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        }
    }

    private Apps() {
    }

    /** TV apps first; phone-style apps are added only if they have no TV entry point. */
    static ArrayList<Entry> load(Context context) {
        PackageManager pm = context.getPackageManager();
        ArrayList<Entry> out = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        seen.add(context.getPackageName());
        collect(pm, Intent.CATEGORY_LEANBACK_LAUNCHER, true, seen, out);
        collect(pm, Intent.CATEGORY_LAUNCHER, false, seen, out);
        final Collator collator = Collator.getInstance();
        Collections.sort(out, (a, b) -> collator.compare(a.label, b.label));
        return out;
    }

    private static void collect(PackageManager pm, String category, boolean leanback,
                                HashSet<String> seen, ArrayList<Entry> out) {
        List<ResolveInfo> found = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        for (int i = 0, n = found.size(); i < n; i++) {
            ResolveInfo ri = found.get(i);
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || !seen.add(ai.packageName)) continue;
            CharSequence label = ri.loadLabel(pm);
            out.add(new Entry(ai.packageName, ai.name,
                    label != null ? label.toString() : ai.packageName, leanback));
        }
    }

    /**
     * Renders the app card (TV banner, or the icon on a plain tile) into a bitmap of
     * exactly the on-screen size.
     *
     * The card is opaque, so RGB_565 loses nothing and takes half the memory of
     * ARGB_8888. Hardware bitmaps were tried and dropped: on some TV chips (seen on
     * an MStar-based Xiaomi) they come out blank, or go blank after the TV idles and
     * the system takes GPU memory back. A plain bitmap is simply uploaded again.
     */
    static Bitmap card(Context context, Entry e, int w, int h) {
        PackageManager pm = context.getPackageManager();
        Drawable banner;
        Drawable icon = null;
        try {
            ActivityInfo ai = pm.getActivityInfo(new ComponentName(e.pkg, e.cls), 0);
            banner = ai.loadBanner(pm);
            if (banner == null) icon = ai.loadIcon(pm);
        } catch (PackageManager.NameNotFoundException | RuntimeException ex) {
            return null;
        }

        Bitmap bmp;
        try {
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas canvas = new Canvas(bmp);
            canvas.drawColor(context.getResources().getColor(R.color.card));
            if (banner != null) {
                int bw = banner.getIntrinsicWidth();
                int bh = banner.getIntrinsicHeight();
                if (bw <= 0 || bh <= 0) {
                    banner.setBounds(0, 0, w, h);
                } else {
                    float s = Math.max(w / (float) bw, h / (float) bh);
                    int dw = Math.round(bw * s);
                    int dh = Math.round(bh * s);
                    int l = (w - dw) / 2;
                    int t = (h - dh) / 2;
                    banner.setBounds(l, t, l + dw, t + dh);
                }
                banner.draw(canvas);
            } else if (icon != null) {
                int s = Math.round(h * 0.6f);
                int l = (w - s) / 2;
                int t = (h - s) / 2;
                icon.setBounds(l, t, l + s, t + s);
                icon.draw(canvas);
            }
        } catch (OutOfMemoryError | RuntimeException ex) {
            return null;
        }
        return bmp;
    }
}
