package com.example.xclick;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.util.ArrayList;
import java.util.List;

/**
 * 哔哩哔哩搜索页 Tab 补全模块 v1.1.0（诊断版）：
 * 搜索页的 Tab 列表由服务端 SearchResultAll.nav 下发（SearchState.nav），
 * 本模块 hook SearchState#getNav() / setNav()，把缺失的 Tab 类型补齐到 6 个：
 * 综合(0)、用户(1)、直播(2)、专栏(3)、番剧(4)、电影(5)。
 * 诊断部分：记录每个 Tab 位置创建的 Fragment、各搜索 API 的调用与返回，定位"补全 Tab 页面空白"的原因。
 */
public class BiliSearchTabsHook implements IXposedHookLoadPackage {

    private static final String TAG = "[BiliSearchTabs]";
    private static final String VERSION = "v1.1.0";
    private static final String TARGET = "com.bilibili.app.in";

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

    private static void logResult(String label, Object res) {
        if (res == null) {
            XposedBridge.log(TAG + " " + label + " 结果=null");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(label).append(" 结果=").append(res.getClass().getName());
        String[] getters = {"getItems", "getVerticalItemList", "getResultList", "getCards"};
        for (String g : getters) {
            try {
                Object v = XposedHelpers.callMethod(res, g);
                if (v instanceof List) {
                    sb.append(" ").append(g).append("=").append(((List<?>) v).size());
                }
            } catch (Throwable ignored) {
            }
        }
        if (sb.indexOf("getItems") < 0) {
            try {
                String s = String.valueOf(res);
                if (s.length() > 120) s = s.substring(0, 120);
                sb.append(" str=").append(s);
            } catch (Throwable ignored) {
            }
        }
        XposedBridge.log(TAG + " " + sb);
    }

    private static void hookApi(ClassLoader cl, String cls, String method, Class<?>[] types, String label) {
        try {
            XposedHelpers.findAndHookMethod(cls, cl, method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        StringBuilder sb = new StringBuilder(label).append(" args=");
                        for (Object a : param.args) {
                            if (a == null) {
                                sb.append("null,");
                            } else if (a instanceof String) {
                                sb.append('"').append(a).append("\",");
                            } else {
                                sb.append(a.getClass().getSimpleName()).append("=").append(a).append(',');
                            }
                        }
                        XposedBridge.log(TAG + " " + sb);
                        logResult(label, param.getResult());
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " " + label + " 记录异常 " + t);
                    }
                }
            }, types);
            XposedBridge.log(TAG + " 已安装API钩子 " + label);
        } catch (Throwable t) {
            XposedBridge.log(TAG + " 安装API钩子失败 " + label + " " + t);
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (!firstLoadLogged) {
            firstLoadLogged = true;
            XposedBridge.log(TAG + " " + VERSION + " 模块已加载，首个包=" + pkg);
        }
        if (!TARGET.equals(pkg)) return;
        ClassLoader cl = lpparam.classLoader;
        try {
            final Class<?> searchState = XposedHelpers.findClass(
                    "com.bilibili.search2.result.base.SearchState", cl);
            final Class<?> navInfo = XposedHelpers.findClass(
                    "com.bilibili.search2.api.SearchResultAll$NavInfo", cl);
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

            Class<?> contImpl = Class.forName("kotlin.coroutines.jvm.internal.ContinuationImpl");

            XposedHelpers.findAndHookMethod("pK0.a", cl, "getItem", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object f = param.getResult();
                        XposedBridge.log(TAG + " getItem(" + param.args[0] + ") -> "
                                + (f == null ? "null" : f.getClass().getName()));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + " getItem 记录异常 " + t);
                    }
                }
            }, int.class);
            XposedBridge.log(TAG + " 已安装 pK0.a.getItem 钩子");

            XposedHelpers.findAndHookMethod(
                    "com.bilibili.app.comm.list.widget.utils.C", cl, "f",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object f = param.getResult();
                                XposedBridge.log(TAG + " C.f uri=" + param.args[2]
                                        + " -> " + (f == null ? "null" : f.getClass().getName()));
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + " C.f 记录异常 " + t);
                            }
                        }
                    },
                    android.content.Context.class, android.os.Bundle.class, String.class);
            XposedBridge.log(TAG + " 已安装 C.f 钩子");

            hookApi(cl, "com.bilibili.search2.result.a", "d",
                    new Class[]{int.class, String.class, String.class, contImpl}, "API.d(直播)");
            hookApi(cl, "com.bilibili.search2.result.a", "c",
                    new Class[]{String.class, int.class, String.class, String.class, String.class,
                            String.class,
                            Class.forName("com.bapis.bilibili.polymer.app.search.v1.UserSort"),
                            Class.forName("com.bapis.bilibili.polymer.app.search.v1.UserType"),
                            java.util.Map.class, contImpl}, "API.c(用户)");
            hookApi(cl, "com.bilibili.search2.result.column.api.b", "a",
                    new Class[]{String.class, String.class, long.class, String.class, String.class,
                            String.class,
                            Class.forName("com.bapis.bilibili.polymer.app.search.v1.CategorySort"),
                            java.util.Map.class, contImpl}, "API.专栏");

            String[] frags = {
                    "com.bilibili.search2.result.vertical.live.SearchResultLiveFragment",
                    "com.bilibili.search2.result.column.SearchResultColumnFragment",
                    "com.bilibili.search2.ogv.OgvSearchResultFragment"};
            for (final String fn : frags) {
                try {
                    XposedHelpers.findAndHookMethod(fn, cl, "onCreate", android.os.Bundle.class,
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) {
                                    try {
                                        android.os.Bundle args = (android.os.Bundle)
                                                XposedHelpers.callMethod(param.thisObject,
                                                        "getArguments");
                                        StringBuilder sb = new StringBuilder();
                                        sb.append(fn.substring(fn.lastIndexOf('.') + 1))
                                                .append(".onCreate args=")
                                                .append(args == null ? "null" : args.keySet().toString());
                                        if (args != null) {
                                            android.os.Bundle b = args.getBundle("default_extra_bundle");
                                            if (b != null) {
                                                sb.append(" extra=").append(b.keySet().toString())
                                                        .append(" keyword=").append(b.getString("keyword"));
                                            }
                                        }
                                        XposedBridge.log(TAG + " " + sb);
                                    } catch (Throwable t) {
                                        XposedBridge.log(TAG + " " + fn + " onCreate 记录异常 " + t);
                                    }
                                }
                            });
                    XposedBridge.log(TAG + " 已安装 onCreate 钩子 " + fn);
                } catch (Throwable t) {
                    XposedBridge.log(TAG + " 安装 onCreate 钩子失败 " + fn + " " + t);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + " init exception " + t);
        }
    }
}