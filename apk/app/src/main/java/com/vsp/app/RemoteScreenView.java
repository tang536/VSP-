package com.vsp.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 远控画面：按 fitCenter 绘制远端帧，并在手指位置绘制鼠标指针。
 *
 * 触摸坐标换算成远端屏幕的归一化坐标（0~1），与 /input 协议一致；
 * 指针由本机立即绘制，不等网络回包，所以移动时不会有“拖影”滞后。
 */
public final class RemoteScreenView extends View {

    public interface Listener {
        void onPointerDown(float x, float y);

        void onPointerMove(float x, float y);

        void onPointerUp(float x, float y, boolean moved);
    }

    private final Paint framePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint cursorFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path cursorPath = new Path();
    private final RectF drawRect = new RectF();
    private final float density;

    private Bitmap frame;
    private Listener listener;
    private boolean cursorVisible;
    private float cursorX = 0.5f, cursorY = 0.5f;
    private float downX, downY;
    private boolean moved;

    public RemoteScreenView(Context context) {
        this(context, null);
    }

    public RemoteScreenView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RemoteScreenView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        density = getResources().getDisplayMetrics().density;
        cursorFill.setColor(Color.WHITE);
        cursorFill.setStyle(Paint.Style.FILL);
        cursorStroke.setColor(0xCC000000);
        cursorStroke.setStyle(Paint.Style.STROKE);
        cursorStroke.setStrokeWidth(Math.max(1f, density));
        setFocusable(true);
        setClickable(true);
        buildCursorPath();
    }

    /** 鼠标箭头按屏幕密度缩放，形状固定，避免每次绘制重新计算路径。 */
    private void buildCursorPath() {
        float s = Math.max(12f, 17f * density) / 17f;
        cursorPath.reset();
        cursorPath.moveTo(0, 0);
        cursorPath.lineTo(0, 17 * s);
        cursorPath.lineTo(4.3f * s, 13.2f * s);
        cursorPath.lineTo(7.1f * s, 20.5f * s);
        cursorPath.lineTo(10.2f * s, 19.1f * s);
        cursorPath.lineTo(7.3f * s, 12.4f * s);
        cursorPath.lineTo(12.2f * s, 12.4f * s);
        cursorPath.close();
    }

    public void setFrame(Bitmap bmp) {
        frame = bmp;
        invalidate();
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public void setCursorVisible(boolean visible) {
        cursorVisible = visible;
        invalidate();
    }

    public void resetCursor() {
        cursorX = 0.5f;
        cursorY = 0.5f;
        invalidate();
    }

    private void updateDrawRect() {
        if (frame == null || frame.isRecycled() || getWidth() <= 0 || getHeight() <= 0) {
            drawRect.setEmpty();
            return;
        }
        float vw = getWidth(), vh = getHeight();
        float scale = Math.min(vw / frame.getWidth(), vh / frame.getHeight());
        float w = frame.getWidth() * scale, h = frame.getHeight() * scale;
        drawRect.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.BLACK);
        if (frame == null || frame.isRecycled()) return;
        updateDrawRect();
        canvas.drawBitmap(frame, null, drawRect, framePaint);
        if (!cursorVisible) return;

        float cx = drawRect.left + cursorX * drawRect.width();
        float cy = drawRect.top + cursorY * drawRect.height();
        // 让箭头整体留在画面内，靠近右/下边缘时指针头仍可见。
        float maxX = Math.max(drawRect.left, drawRect.right - 13 * density);
        float maxY = Math.max(drawRect.top, drawRect.bottom - 21 * density);
        cx = Math.max(drawRect.left, Math.min(cx, maxX));
        cy = Math.max(drawRect.top, Math.min(cy, maxY));
        canvas.save();
        canvas.translate(cx, cy);
        canvas.drawPath(cursorPath, cursorFill);
        canvas.drawPath(cursorPath, cursorStroke);
        canvas.restore();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (listener == null || frame == null) return false;
        if (drawRect.isEmpty()) updateDrawRect();
        if (drawRect.isEmpty()) return false;

        float x = (e.getX() - drawRect.left) / drawRect.width();
        float y = (e.getY() - drawRect.top) / drawRect.height();
        x = Math.max(0f, Math.min(1f, x));
        y = Math.max(0f, Math.min(1f, y));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                moved = false;
                cursorX = x;
                cursorY = y;
                cursorVisible = true;
                invalidate();
                listener.onPointerDown(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (Math.abs(x - downX) > 0.012f || Math.abs(y - downY) > 0.012f) moved = true;
                cursorX = x;
                cursorY = y;
                invalidate();
                listener.onPointerMove(x, y);
                return true;
            case MotionEvent.ACTION_UP:
                cursorX = x;
                cursorY = y;
                invalidate();
                listener.onPointerUp(x, y, moved);
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cursorX = x;
                cursorY = y;
                invalidate();
                listener.onPointerUp(x, y, true);
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        return true;
    }
}
