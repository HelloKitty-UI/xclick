package com.example.bilivolume;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String BILI_PACKAGE = "com.bilibili.app.in";
    private static final String TARGET_VIEW_ID = "gemini_halfscreen_expand";

    private int cachedResId = 0;
    private long lastTriggerTime = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!BILI_PACKAGE.equals(lpparam.packageName)) {
            return;
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
                        lastTriggerTime = now;

                        Activity activity = (Activity) param.thisObject;
                        int resId = getExpandViewResId(activity);
                        if (resId == 0) {
                            return;
                        }

                        View expandView = activity.findViewById(resId);
                        if (expandView == null || !expandView.isShown()) {
                            return;
                        }

                        expandView.performClick();
                        param.setResult(true);
                    }
                });
    }

    private int getExpandViewResId(Activity activity) {
        if (cachedResId != 0) {
            return cachedResId;
        }
        cachedResId = activity.getResources()
                .getIdentifier(TARGET_VIEW_ID, "id", BILI_PACKAGE);
        return cachedResId;
    }
}
