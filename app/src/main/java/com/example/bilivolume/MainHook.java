package com.example.bilivolume;

import android.app.Activity;
import android.content.res.Configuration;
import android.text.Spanned;
import android.text.style.ClickableSpan;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String BILI_PACKAGE = "com.bilibili.app.in";
    private static final String WIDGET_CLASS =
            "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget";
    private static final String PLUGIN_COMMENT_WIDGET_ID = "plugin_comment_widget";
    private static final String REPLY_TEXT_REGEX = "共[\\d,，.万wW]+条回复";
    private static final int REPLY_CHILD_INDEX = 3;

    private Object widgetRef;
    private int pluginWidgetResId;
    private long lastTriggerTime;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!BILI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(WIDGET_CLASS, lpparam.classLoader, "m2",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            widgetRef = param.thisObject;
                            XposedBridge.log("BiliFullscreen: widget bound");
                        }
                    });

            XposedHelpers.findAndHookMethod(WIDGET_CLASS, lpparam.classLoader, "F1",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            if (param.thisObject == widgetRef) {
                                widgetRef = null;
                                XposedBridge.log("BiliFullscreen: widget unbound");
                            }
                        }
                    });
        } catch (Throwable t) {
            XposedBridge.log("BiliFullscreen: failed to hook widget");
            XposedBridge.log(t);
        }

        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        KeyEvent event = (KeyEvent) param.args[0];
                        if (event == null || event.getAction() != KeyEvent.ACTION_DOWN) {
                            return;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastTriggerTime < 500) {
                            return;
                        }

                        Activity activity = (Activity) param.thisObject;
                        int keyCode = event.getKeyCode();
                        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                            maybeEnterFullscreen(activity, param, now);
                        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                            expandNearestReply(activity, param, now);
                        }
                    }
                });
    }

    private void maybeEnterFullscreen(Activity activity, XC_MethodHook.MethodHookParam param, long now) {
        if (activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }

        Object widget = widgetRef;
        if (!(widget instanceof View)) {
            return;
        }
        View widgetView = (View) widget;
        if (widgetView.getContext() instanceof Activity
                && widgetView.getContext() != activity) {
            return;
        }

        lastTriggerTime = now;
        try {
            XposedHelpers.callMethod(widget, "onClick", widget);
            param.setResult(true);
            XposedBridge.log("BiliFullscreen: widget.onClick fired");
        } catch (Throwable t) {
            XposedBridge.log("BiliFullscreen: onClick failed");
            XposedBridge.log(t);
        }
    }

    private void expandNearestReply(Activity activity, XC_MethodHook.MethodHookParam param, long now) {
        if (activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            return;
        }

        View decor = activity.getWindow().getDecorView();
        if (decor == null) {
            return;
        }

        DisplayMetrics dm = activity.getResources().getDisplayMetrics();
        List<View> targets = collectReplyCandidates(decor);
        XposedBridge.log("BiliFullscreen: plugin candidates=" + targets.size());
        View target = pickClosest(targets, dm.widthPixels / 2, dm.heightPixels / 2);
        if (target == null) {
            XposedBridge.log("BiliFullscreen: no target, pluginResId="
                    + Integer.toHexString(getPluginWidgetResId(decor)));
            dumpPluginStructure(decor);
            return;
        }

        lastTriggerTime = now;
        boolean clicked = triggerClick(target);
        XposedBridge.log("BiliFullscreen: click result=" + clicked
                + " targetClass=" + target.getClass().getName()
                + " clickable=" + target.isClickable());
        if (clicked) {
            param.setResult(true);
        }
    }

    private List<View> collectReplyCandidates(View root) {
        List<View> targets = new ArrayList<>();
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v instanceof ViewGroup && v.getId() == getPluginWidgetResId(v)) {
                View child = findReplyChild((ViewGroup) v);
                if (child != null && child.isShown()) {
                    targets.add(child);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(vg.getChildAt(i));
                }
            }
        }
        if (targets.isEmpty()) {
            targets = findReplyByText(root);
        }
        return targets;
    }

    private void dumpPluginStructure(View root) {
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        int dumped = 0;
        while (!stack.isEmpty() && dumped < 3) {
            View v = stack.pop();
            if (v instanceof ViewGroup && v.getId() == getPluginWidgetResId(v)) {
                ViewGroup vg = (ViewGroup) v;
                StringBuilder sb = new StringBuilder("plugin widget children=" + vg.getChildCount() + ":");
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View c = vg.getChildAt(i);
                    String text = "";
                    if (c instanceof TextView && ((TextView) c).getText() != null) {
                        text = ((TextView) c).getText().toString();
                    }
                    sb.append(" [").append(i).append("]").append(c.getClass().getSimpleName())
                            .append(" clickable=").append(c.isClickable())
                            .append(" text='").append(text).append("'");
                }
                XposedBridge.log("BiliFullscreen: " + sb);
                dumped++;
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(vg.getChildAt(i));
                }
            }
        }
        if (dumped == 0) {
            XposedBridge.log("BiliFullscreen: no plugin_comment_widget found in tree");
        }
    }

    private View findReplyChild(ViewGroup widget) {
        int count = widget.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = widget.getChildAt(i);
            if (child instanceof TextView) {
                CharSequence cs = ((TextView) child).getText();
                if (cs != null && cs.toString().trim().matches(REPLY_TEXT_REGEX)) {
                    return child;
                }
            }
        }
        if (count > REPLY_CHILD_INDEX) {
            View child = widget.getChildAt(REPLY_CHILD_INDEX);
            if (child.isShown()) {
                return child;
            }
        }
        return null;
    }

    private List<View> findReplyByText(View root) {
        List<View> targets = new ArrayList<>();
        ArrayDeque<View> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                if (tv.isShown() && tv.getText() != null
                        && tv.getText().toString().trim().matches(REPLY_TEXT_REGEX)) {
                    targets.add(v);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) v;
                for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(vg.getChildAt(i));
                }
            }
        }
        return targets;
    }

    private View pickClosest(List<View> views, int centerX, int centerY) {
        View best = null;
        long bestDist = Long.MAX_VALUE;
        for (View v : views) {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            long dx = loc[0] + (long) v.getWidth() / 2 - centerX;
            long dy = loc[1] + (long) v.getHeight() / 2 - centerY;
            long dist = dx * dx + dy * dy;
            if (dist < bestDist) {
                bestDist = dist;
                best = v;
            }
        }
        return best;
    }

    private int getPluginWidgetResId(View anyView) {
        if (pluginWidgetResId != 0) {
            return pluginWidgetResId;
        }
        pluginWidgetResId = anyView.getResources()
                .getIdentifier(PLUGIN_COMMENT_WIDGET_ID, "id", BILI_PACKAGE);
        return pluginWidgetResId;
    }

    private boolean triggerClick(View v) {
        if (v.performClick()) {
            return true;
        }

        if (v instanceof TextView) {
            CharSequence cs = ((TextView) v).getText();
            if (cs instanceof Spanned) {
                Spanned sp = (Spanned) cs;
                ClickableSpan[] spans = sp.getSpans(0, cs.length(), ClickableSpan.class);
                if (spans.length > 0) {
                    try {
                        spans[0].onClick(v);
                        return true;
                    } catch (Throwable t) {
                        XposedBridge.log("BiliFullscreen: clickable span failed");
                        XposedBridge.log(t);
                    }
                }
            }
        }

        View p = (View) v.getParent();
        int depth = 0;
        while (p != null && depth < 3) {
            if (p.isClickable() && p.performClick()) {
                return true;
            }
            p = (View) p.getParent();
            depth++;
        }
        return false;
    }
}
