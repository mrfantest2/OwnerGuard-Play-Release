package com.fantest.ownerguard;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AspectTextureView extends TextureView {
    private int ratioWidth;
    private int ratioHeight;
    public AspectTextureView(Context c) { super(c); }
    public AspectTextureView(Context c, AttributeSet a) { super(c, a); }
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) throw new IllegalArgumentException("Invalid video dimensions");
        ratioWidth = width; ratioHeight = height; requestLayout();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int height = MeasureSpec.getSize(heightSpec);
        if (ratioWidth == 0 || ratioHeight == 0) setMeasuredDimension(width, height);
        else if (width < height * ratioWidth / ratioHeight) setMeasuredDimension(width, width * ratioHeight / ratioWidth);
        else setMeasuredDimension(height * ratioWidth / ratioHeight, height);
    }
}
