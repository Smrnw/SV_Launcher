package com.smrnw.svlauncher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-screen background: a center-cropped wallpaper with a cross-fade between
 * images and a readability gradient, or a plain dark color when there is none.
 * Everything is drawn by this single view to avoid extra layers and overdraw.
 */
public final class WallpaperView extends View {

    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint scrim = new Paint();
    private final Rect src = new Rect();
    private final Rect dst = new Rect();
    private final int background;
    private final ValueAnimator fadeAnimator = ValueAnimator.ofFloat(0f, 1f);

    private Bitmap current;
    private Bitmap previous;
    private float fade = 1f;
    private int dim;

    public WallpaperView(Context context, AttributeSet attrs) {
        super(context, attrs);
        background = context.getResources().getColor(R.color.bg);
        fadeAnimator.setDuration(700);
        fadeAnimator.addUpdateListener(a -> {
            fade = (Float) a.getAnimatedValue();
            invalidate();
        });
        fadeAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                fade = 1f;
                release(previous);
                previous = null;
                invalidate();
            }
        });
    }

    boolean isEmpty() {
        return current == null;
    }

    /** Takes ownership of the bitmap and fades it in over the previous one. */
    void show(Bitmap bitmap) {
        fadeAnimator.cancel(); // finishes a running fade and frees its "previous"
        long transitionLimit = Math.min(12L * 1024 * 1024,
                Runtime.getRuntime().maxMemory() / 24);
        if (current != null && current.getByteCount() + (long) bitmap.getByteCount() > transitionLimit) {
            release(current);
            release(previous);
            current = bitmap;
            previous = null;
            fade = 1f;
            invalidate();
            return;
        }
        previous = current;
        current = bitmap;
        fade = 0f;
        fadeAnimator.start();
    }

    /** Frees all bitmaps and falls back to the plain dark background. */
    void clear() {
        fadeAnimator.cancel();
        release(current);
        release(previous);
        current = null;
        previous = null;
        fade = 1f;
        invalidate();
    }

    private static void release(Bitmap b) {
        if (b != null && !b.isRecycled()) b.recycle();
    }

    /** Extra darkening of the wallpaper in percent, on top of the readability gradient. */
    void setDim(int percent) {
        int value = Math.max(0, Math.min(100, percent)) * 255 / 100;
        if (value == dim) return;
        dim = value;
        buildScrim(getHeight());
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        buildScrim(h);
    }

    // The darkening is baked into the gradient instead of being one more
    // full-screen layer on top of it.
    private void buildScrim(int height) {
        if (height <= 0) return;
        scrim.setShader(new LinearGradient(0, 0, 0, height,
                new int[]{shade(0x4D), shade(0x14), shade(0xBF)},
                new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
    }

    /** Black with the opacity of two stacked layers: the gradient's own and the dimming. */
    private int shade(int alpha) {
        return (255 - (255 - alpha) * (255 - dim) / 255) << 24;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (current == null) {
            canvas.drawColor(background);
            return;
        }
        if (fade < 1f) {
            if (previous != null) {
                drawCropped(canvas, previous, 255);
            } else {
                canvas.drawColor(background);
            }
            drawCropped(canvas, current, (int) (fade * 255));
        } else {
            drawCropped(canvas, current, 255);
        }
        canvas.drawRect(0, 0, getWidth(), getHeight(), scrim);
    }

    private void drawCropped(Canvas canvas, Bitmap b, int alpha) {
        if (b.isRecycled()) return;
        int vw = getWidth();
        int vh = getHeight();
        int bw = b.getWidth();
        int bh = b.getHeight();
        float s = Math.max(vw / (float) bw, vh / (float) bh);
        int sw = Math.min(bw, Math.round(vw / s));
        int sh = Math.min(bh, Math.round(vh / s));
        int l = (bw - sw) / 2;
        int t = (bh - sh) / 2;
        src.set(l, t, l + sw, t + sh);
        dst.set(0, 0, vw, vh);
        paint.setAlpha(alpha);
        canvas.drawBitmap(b, src, dst, paint);
    }
}
