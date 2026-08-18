package com.example.xclick;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩搜索页 Tab 补全模块：
 * 搜索页的 Tab 列表由服务端 SearchResultAll.nav 下发（SearchState.nav），
 * 本模块 hook SearchState#getNav()，把缺失的 Tab 类型补齐到 6 个：
 * 综合(0)、用户(1)、直播(2)、专栏(3)、番剧(4)、电影(5)。
 * 每个 Tab 对应一个 id 为 tab_title 的 TintTextView，由 PagerSlidingTabStrip 动态创建。
 */
public class BiliSearchTabsHook implements IXposedHookLoadPackage {

    private static final String TAG = "[BiliSearchTabs]";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"tv.danmaku.bili".equals(lpparam.packageName)) return;
        try {
            final Class<?> searchState = XposedHelpers.findClass(
                    "com.bilibili.search2.result.base.SearchState", lpparam.classLoader);
            final Class<?> navInfo = XposedHelpers.findClass(
                    "com.bilibili.search2.api.SearchResultAll$NavInfo", lpparam.classLoader);

            final int[] types = {0, 1, 2, 3, 4, 5};
            final String[] names = {"综合", "用户", "直播", "专栏", "番剧", "电影"};

            XposedHelpers.findAndHookMethod(searchState, "getNav", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        List<?> nav = (List<?>) param.getResult();
                        boolean[] present = new boolean[types.length];
                        if (nav != null) {
                            for (Object o : nav) {
                                int t = (Integer) XposedHelpers.callMethod(o, "getType");
                                if (t >= 0 && t < present.length) present[t] = true;
                            }
                        }
                        List<Object> full = nav == null ? new ArrayList<Object>() : new ArrayList<Object>(nav);
                        int added = 0;
                        for (int i = 0; i < types.length; i++) {
                            if (present[types[i]]) continue;
                            Object ni = XposedHelpers.newInstance(navInfo);
                            XposedHelpers.callMethod(ni, "setName", names[i]);
                            XposedHelpers.callMethod(ni, "setType", types[i]);
                            XposedHelpers.callMethod(ni, "setTotal", 0);
                            XposedHelpers.callMethod(ni, "setPages", 0);
                            full.add(ni);
                            added++;
                        }
                        param.setResult(full);
                        if (added > 0) {
                            XposedBridge.log(TAG + " nav tabs=" + nav.size() + " -> " + full.size() + " 新增 " + added);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " hook exception " + t);
                    }
                }
            });
            XposedBridge.log(TAG + " installed for tv.danmaku.bili");
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init exception " + t);
        }
    }
}
