package com.example.xclick;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩搜索页 Tab 补全模块 v1.0.9：
 * 搜索页的 Tab 列表由服务端 SearchResultAll.nav 下发（SearchState.nav），
 * 本模块 hook SearchState#getNav() / setNav()，把缺失的 Tab 类型补齐到 6 个：
 * 综合(0)、用户(1)、直播(2)、专栏(3)、番剧(4)、电影(5)。
 * 每个 Tab 对应一个 id 为 tab_title 的 TintTextView，由 PagerSlidingTabStrip 动态创建。
 */
public class BiliSearchTabsHook implements IXposedHookLoadPackage {

    private static final String TAG = "[BiliSearchTabs]";
    private static final String VERSION = "v1.0.9";
    private static final String TARGET = "tv.danmaku.bili";

    private static volatile boolean firstLoadLogged = false;

    private static List<Object> completeNav(List<?> nav, Class<?> navInfo) {
        final int[] types = {0, 1, 2, 3, 4, 5};
        final String[] names = {"综合", "用户", "直播", "专栏", "番剧", "电影"};
        boolean[] present = new boolean[types.length];
        if (nav != null) {
            for (Object o : nav) {
                if (o == null) continue;
                try {
                    int t = (Integer) XposedHelpers.callMethod(o, "getType");
                    if (t >= 0 && t < present.length) present[t] = true;
                } catch (Throwable ignored) {
                }
            }
        }
        List<Object> full = nav == null ? new ArrayList<Object>() : new ArrayList<Object>(nav);
        int added = 0;
        for (int i = 0; i < types.length; i++) {
            if (present[types[i]]) continue;
            try {
                Object ni = XposedHelpers.newInstance(navInfo);
                XposedHelpers.callMethod(ni, "setName", names[i]);
                XposedHelpers.callMethod(ni, "setType", types[i]);
                XposedHelpers.callMethod(ni, "setTotal", 0);
                XposedHelpers.callMethod(ni, "setPages", 0);
                full.add(ni);
                added++;
            } catch (Throwable t) {
                XposedBridge.log(TAG + " build NavInfo type=" + types[i] + " 失败 " + t);
            }
        }
        if (added > 0) {
            XposedBridge.log(TAG + " nav " + (nav == null ? "null" : nav.size())
                    + " -> " + full.size() + " 新增 " + added);
        }
        return full;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!firstLoadLogged) {
            firstLoadLogged = true;
            XposedBridge.log(TAG + " " + VERSION + " 模块已加载，首个包=" + pkg);
        }
        if (!TARGET.equals(pkg)) return;
        try {
            XposedBridge.log(TAG + " 进入 tv.danmaku.bili，开始找类");
            final Class<?> searchState = XposedHelpers.findClass(
                    "com.bilibili.search2.result.base.SearchState", lpparam.classLoader);
            final Class<?> navInfo = XposedHelpers.findClass(
                    "com.bilibili.search2.api.SearchResultAll$NavInfo", lpparam.classLoader);
            XposedBridge.log(TAG + " 类查找成功: " + searchState.getName() + " / " + navInfo.getName());

            XposedHelpers.findAndHookMethod(searchState, "getNav", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        List<?> nav = (List<?>) param.getResult();
                        List<Object> full = completeNav(nav, navInfo);
                        param.setResult(full);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " getNav hook 异常 " + t);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(searchState, "setNav", List.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        List<?> nav = (List<?>) param.args[0];
                        param.args[0] = completeNav(nav, navInfo);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " setNav hook 异常 " + t);
                    }
                }
            });

            XposedBridge.log(TAG + " " + VERSION + " getNav+setNav 钩子已安装");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init exception " + t);
        }
    }
}