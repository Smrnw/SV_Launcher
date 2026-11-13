package com.smrnw.svlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/** 16:9 app card with rounded corners. The outline also gives the focus shadow. */
public final class BannerView extends ImageView {

    private final int placeholder;

    public BannerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        placeholder = context.getResources().getColor(R.color.card);
        final float radius = context.getResources().getDimension(R.dimen.card_radius);
        setScaleType(ScaleType.FIT_XY);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(w, w * 9 / 16);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getDrawable() == null) {
            canvas.drawColor(placeholder);
        } else {
            super.onDraw(canvas);
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }
}
