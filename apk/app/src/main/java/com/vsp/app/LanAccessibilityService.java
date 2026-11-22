package com.vsp.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.view.accessibility.AccessibilityEvent;

/** 局域网远控的被控端：用无障碍手势注入点击 / 滑动 / 系统按键。 */
public final class LanAccessibilityService extends AccessibilityService {

    private static volatile LanAccessibilityService instance;
    public static LanAccessibilityService getInstance() { return instance; }

    @Override public void onServiceConnected() { super.onServiceConnected(); instance = this; }
    @Override public void onDestroy() { instance = null; super.onDestroy(); }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    public boolean tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 120);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    public boolean swipe(float x1, float y1, float x2, float y2) {
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 300);
        return dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    public boolean key(String k) {
        int action;
        if ("back".equals(k)) action = GLOBAL_ACTION_BACK;
        else if ("home".equals(k)) action = GLOBAL_ACTION_HOME;
        else if ("recent".equals(k)) action = GLOBAL_ACTION_RECENTS;
        else return false;
        return performGlobalAction(action);
    }
}
