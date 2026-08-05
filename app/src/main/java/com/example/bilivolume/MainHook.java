package com.example.bilivolume;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.KeyEvent;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String BILI_PACKAGE = "com.bilibili.app.in";
    private static final String WIDGET_CLASS =
            "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget";

    private Object widgetRef;
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
            XposedBridge.log("BiliFullscreen: failed to hook widget", t);
        }

        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        KeyEvent event = (KeyEvent) param.args[0];
                        if (event == null
                                || event.getKeyCode() != KeyEvent.KEYCODE_VOLUME_DOWN
                                || event.getAction() != KeyEvent.ACTION_DOWN) {
                            return;
                        }

                        long now = System.currentTimeMillis();
                        if (now - lastTriggerTime < 500) {
                            return;
                        }

                        Activity activity = (Activity) param.thisObject;
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
                            XposedBridge.log("BiliFullscreen: onClick failed", t);
                        }
                    }
                });
    }
}
