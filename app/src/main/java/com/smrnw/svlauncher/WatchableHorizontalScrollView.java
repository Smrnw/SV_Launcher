package com.smrnw.svlauncher;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/** Reports visible-row changes so off-screen app cards can release their bitmaps. */
public final class WatchableHorizontalScrollView extends HorizontalScrollView {

    private Runnable scrollListener;

    public WatchableHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setScrollListener(Runnable listener) {
        scrollListener = listener;
    }

    @Override
    protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY);
        if (scrollListener != null) scrollListener.run();
    }
}
