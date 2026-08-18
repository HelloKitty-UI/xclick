package com.example.xclick;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

public class BiliSearchTabsHook implements IXposedHookLoadPackage {

    private static final String TARGET = "com.bilibili.app.in";
    private static final int COLUMN_TYPE = 6;
    private static final String COLUMN_NAME = "专栏";

    private static List<?> completeNav(List<?> nav, Class<?> navInfo) {
        if (nav != null) {
            for (Object o : nav) {
                if (o == null) continue;
                try {
                    Object type = XposedHelpers.callMethod(o, "getType");
                    if (type != null && ((Integer) type).intValue() == COLUMN_TYPE) {
                        return nav;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<Object> full = nav == null ? new ArrayList<Object>() : new ArrayList<Object>(nav);
        try {
            Object ni = XposedHelpers.newInstance(navInfo);
            XposedHelpers.callMethod(ni, "setName", COLUMN_NAME);
            XposedHelpers.callMethod(ni, "setType", COLUMN_TYPE);
            XposedHelpers.callMethod(ni, "setTotal", 0);
            XposedHelpers.callMethod(ni, "setPages", 0);
            full.add(ni);
        } catch (Throwable t) {
            XposedBridge.log("[BiliSearchTabs] build NavInfo failed: " + t);
        }
        return full;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET.equals(lpparam.packageName)) {
            return;
        }
        try {
            Class<?> searchState = XposedHelpers.findClass(
                    "com.bilibili.search2.result.base.SearchState", lpparam.classLoader);
            Class<?> navInfo = XposedHelpers.findClass(
                    "com.bilibili.search2.api.SearchResultAll$NavInfo", lpparam.classLoader);

            XposedHelpers.findAndHookMethod(searchState, "getNav", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        List<?> nav = (List<?>) param.getResult();
                        param.setResult(completeNav(nav, navInfo));
                    } catch (Throwable ignored) {
                    }
                }
            });

            XposedHelpers.findAndHookMethod(searchState, "setNav", List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        List<?> nav = (List<?>) param.args[0];
                        param.args[0] = completeNav(nav, navInfo);
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}