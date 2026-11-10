package com.vsp.app;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * 高度跟随宽度、固定 1:1 的容器。
 *
 * 网格卡片原来写死 72dp 高：三列布局里每格宽度约 110dp，卡片就被压成一条扁片，
 * 照片根本看不出内容。交给它在测量阶段把高度对齐宽度，任意屏宽都是正方形，
 * 也不会像布局里写死 dp 那样在大屏上显得空。
 */
public class RatioFrameLayout extends FrameLayout {

    public RatioFrameLayout(Context context) {
        super(context);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioFrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED || width <= 0) {
            super.onMeasure(widthSpec, heightSpec);
            width = getMeasuredWidth();
        }
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY));
    }
}
