package com.example.xclick;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHook implements IXposedHookLoadPackage {

    private static final String CONFIG_PREFS = "xclick_config";
    private static final String CONFIG_KEY = "config";
    private static final java.util.regex.Pattern REPLY_TEXT =
            java.util.regex.Pattern.compile("共[0-9][0-9,，.万wW]*条回复");

    private void hookSystemDisplayRotation(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            java.io.File conf = new java.io.File("/data/data/com.example.xclick/files/xclick.conf");
            XposedBridge.log("[XClick] DR270 sys-load confExists=" + conf.exists()
                    + " confCanRead=" + conf.canRead());
            Class<?> clazz = XposedHelpers.findClass(
                    "com.android.server.wm.DisplayRotation", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(clazz, "rotationForOrientation",
                    int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            boolean enabled;
                            try {
                                enabled = isRotate270Enabled();
                            } catch (Throwable t) {
                                enabled = false;
                                XposedBridge.log("[XClick] DR270 flag-read error");
                                XposedBridge.log(t);
                            }
                            int orientation = ((Integer) param.args[0]).intValue();
                            int rotation = ((Integer) param.getResult()).intValue();
                            XposedBridge.log("[XClick] DR270 dbg o=" + orientation
                                    + " r=" + rotation + " enabled=" + enabled);
                            if (enabled && rotation == android.view.Surface.ROTATION_90
                                    && isLandscapeOrientation(orientation)) {
                                param.setResult(android.view.Surface.ROTATION_270);
                                XposedBridge.log("[XClick] DR270 FLIP orientation=" + orientation
                                        + " ROTATION_90 -> ROTATION_270");
                            }
                        }
                    });
            XposedBridge.log("[XClick] DR270 hooked OK bootFlag=" + isRotate270Enabled());
        } catch (Throwable t) {
            XposedBridge.log("[XClick] DR270 hook失败");
            XposedBridge.log(t);
        }
    }

    private static boolean isLandscapeOrientation(int orientation) {
        switch (orientation) {
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE:
            case android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER:
                return true;
            default:
                return false;
        }
    }

    private static long flagCacheAt = 0;
    private static boolean flagCacheVal = false;

    private static boolean isRotate270Enabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - flagCacheAt < 2000) return flagCacheVal;
        String text = XConfig.readFile(new File("/data/data/com.example.xclick/files/xclick.conf"));
        if (text == null || text.trim().isEmpty()) {
            try {
                de.robv.android.xposed.XSharedPreferences prefs =
                        new de.robv.android.xposed.XSharedPreferences("com.example.xclick", CONFIG_PREFS);
                prefs.makeWorldReadable();
                prefs.reload();
                if (prefs.getFile() != null && prefs.getFile().canRead()) {
                    text = prefs.getString(CONFIG_KEY, "");
                }
            } catch (Throwable t) {
            }
        }
        flagCacheAt = now;
        flagCacheVal = parseRotate270Flag(text);
        return flagCacheVal;
    }

    private static boolean parseRotate270Flag(String text) {
        if (text == null) return false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            if (k.equals("rotate_270") || k.equals("rotate270")) {
                return !t.substring(eq + 1).trim().equals("0");
            }
        }
        return false;
    }

    private long lastTrigger = 0;
    private long lastKeyWrite = 0;
    private long lastLocalClick = 0;
    private volatile boolean watcherStarted = false;
    private volatile boolean activityStopped = false;
    private volatile long clickTime = 0;
    private volatile long lastUserKey = 0;
    private volatile long lastUserTouch = 0;
    private String pkg;
    private XC_LoadPackage.LoadPackageParam lp;
    private XConfig cfg;
    private boolean cfgLoadedOnce = false;
    private final Map<String, Integer> resIdCache = new HashMap<String, Integer>();
    private WeakReference<Activity> currentActivity = new WeakReference<Activity>(null);
    private String triggerPath = null;

    private static XConfig tryFromFile(String path) {
        if (path == null) return null;
        String text = XConfig.readFile(new File(path));
        if (text == null || text.trim().isEmpty()) return null;
        return XConfig.parse(text);
    }

    private static XConfig tryFromPrefs() {
        try {
            de.robv.android.xposed.XSharedPreferences p =
                    new de.robv.android.xposed.XSharedPreferences("com.example.xclick", CONFIG_PREFS);
            p.makeWorldReadable();
            if (p.getFile() == null || !p.getFile().canRead()) return null;
            String text = p.getString(CONFIG_KEY, "");
            if (text == null || text.trim().isEmpty()) return null;
            return XConfig.parse(text);
        } catch (Throwable t) {
            return null;
        }
    }

    private XConfig loadConfig() {
        XConfig c = tryFromFile("/data/user/0/com.example.xclick/files/xclick.conf");
        if (c == null) c = tryFromPrefs();
        if (c == null) {
            String ext = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            c = tryFromFile(ext + "/ClickTrigger/config.properties");
            if (c == null) c = tryFromFile("/storage/emulated/0/ClickTrigger/config.properties");
        }
        if (c == null) {
            c = XConfig.parse(XConfig.template());
        }
        return c;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            hookSystemDisplayRotation(lpparam);
            return;
        }
        if (lpparam.packageName.equals("com.example.xclick")) return;
        try {
            cfg = loadConfig();
        } catch (Throwable t) {
            XposedBridge.log("[XClick] 配置加载失败 " + t);
            return;
        }
        boolean anyMatch = false;
        for (XConfig.Profile p : cfg.profiles) {
            if (p.matchesPackage(lpparam.packageName)) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) return;
        if (!cfgLoadedOnce) {
            cfgLoadedOnce = true;
            XposedBridge.log("[XClick] 当前应用=" + lpparam.packageName + " 只打印匹配本应用的配置:");
            for (XConfig.Profile p : cfg.profiles) {
                if (!p.matchesPackage(lpparam.packageName)) continue;
                XposedBridge.log("[XClick] 配置: [" + p.name + "] key=" + p.keyName
                        + " view=" + p.viewId + " child=" + p.childText);
            }
        }

        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent",
                KeyEvent.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object act = param.thisObject;
                            if (act instanceof Activity) {
                                currentActivity = new WeakReference<Activity>((Activity) act);
                            }
                            KeyEvent event = (KeyEvent) param.args[0];
                            if (event == null) return;
                            if (event.getAction() != KeyEvent.ACTION_DOWN) return;
                            lastUserKey = System.currentTimeMillis();
                            boolean pkgWanted = false;
                            for (XConfig.Profile p : cfg.profiles) {
                                if (p.matchesPackage(lpparam.packageName)
                                        && p.matchesKey(event.getKeyCode())) {
                                    pkgWanted = true;
                                    break;
                                }
                            }
                            if (!pkgWanted) return;
                            try {
                                XConfig fresh = loadConfig();
                                if (fresh != null && !fresh.profiles.isEmpty()) {
                                    cfg = fresh;
                                }
                            } catch (Throwable t) {
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastTrigger < cfg.debounceMs) return;
                            lastTrigger = now;
                            boolean handled = false;
                            for (XConfig.Profile p : cfg.profiles) {
                                if (!p.matchesPackage(lpparam.packageName)) continue;
                                if (!p.matchesKey(event.getKeyCode())) continue;
                                try {
                                    if (trigger(p, (Activity) act, lpparam)) {
                                        handled = true;
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log("[XClick] 触发异常 " + t);
                                }
                            }
                            long nowMs = System.currentTimeMillis();
                            lastLocalClick = nowMs;
                            if (cfg.consumeKey) {
                                param.setResult(true);
                            }
                            if (!handled) {
                                lastTrigger = 0;
                            }
                            XposedBridge.log("[XClick] 按键" + event.getKeyCode() + " 本包=" + lpparam.packageName
                                    + " 处理=" + handled + " 消费=" + cfg.consumeKey);
                            writeKeyTrigger(event.getKeyCode());
                        } catch (Throwable t) {
                            XposedBridge.log("[XClick] hook 异常 " + t);
                        }
                    }
                });
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.thisObject instanceof Activity) {
                            currentActivity = new WeakReference<Activity>((Activity) param.thisObject);
                        }
                    } catch (Throwable t) {
                    }
                }
            });
        } catch (Throwable t) {
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onStop", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    activityStopped = true;
                }
            });
        } catch (Throwable t) {
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent",
                    android.view.MotionEvent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            lastUserTouch = System.currentTimeMillis();
                        }
                    });
        } catch (Throwable t) {
        }
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onBackPressed", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    lastUserKey = System.currentTimeMillis();
                }
            });
        } catch (Throwable t) {
        }
        try {
            Class<?> cb = Class.forName("android.media.session.MediaSession$Callback");
            XposedHelpers.findAndHookMethod(cb, "onMediaButtonEvent",
                    android.content.Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                android.content.Intent it = (android.content.Intent) param.args[0];
                                KeyEvent ke = (KeyEvent) it.getParcelableExtra(
                                        android.content.Intent.EXTRA_KEY_EVENT);
                                if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                                    writeKeyTrigger(ke.getKeyCode());
                                }
                            } catch (Throwable t2) {
                            }
                        }
                    });
        } catch (Throwable t) {
        }
        pkg = lpparam.packageName;
        try {
            triggerPath = lpparam.appInfo.dataDir + "/files/xclick_trigger.txt";
        } catch (Throwable t) {
        }
        lp = lpparam;
        startWatcher();
    }

    private void writeKeyTrigger(int keyCode) {
        try {
            if (triggerPath == null || pkg == null || cfg == null) return;
            if (!anyKeyMatches(keyCode)) return;
            long now = System.currentTimeMillis();
            if (now - lastKeyWrite < cfg.debounceMs) return;
            lastKeyWrite = now;
            String tmp = triggerPath + ".tmp";
            File tf = new File(tmp);
            File dir = tf.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(tf);
            fos.write((keyCode + "\n" + now + "\n").getBytes("UTF-8"));
            fos.close();
            tf.renameTo(new File(triggerPath));
        } catch (Throwable t) {
        }
    }

    private boolean anyKeyMatches(int keyCode) {
        try {
            for (XConfig.Profile p : cfg.profiles) {
                if (p.matchesPackage(pkg) && p.matchesKey(keyCode)) return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private void startWatcher() {
        if (watcherStarted) return;
        watcherStarted = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(120);
                    } catch (InterruptedException e) {
                        return;
                    }
                    try {
                        if (triggerPath == null) {
                            Thread.sleep(1000);
                            continue;
                        }
                        File f = new File(triggerPath);
                        if (!f.exists()) continue;
                        int keyCode = 0;
                        long t = 0;
                        try {
                            java.io.BufferedReader r = new java.io.BufferedReader(
                                    new java.io.InputStreamReader(
                                            new java.io.FileInputStream(f), "UTF-8"));
                            String l1 = r.readLine();
                            String l2 = r.readLine();
                            r.close();
                            if (l1 != null) keyCode = Integer.parseInt(l1.trim());
                            if (l2 != null) t = Long.parseLong(l2.trim());
                        } catch (Throwable t2) {
                        }
                        if (keyCode <= 0 || t <= 0) {
                            f.delete();
                            continue;
                        }
                        File lock = new File(triggerPath + ".lk");
                        if (!f.renameTo(lock)) continue;
                        long now = System.currentTimeMillis();
                        if (now - lastLocalClick < cfg.debounceMs) {
                            lock.delete();
                            continue;
                        }
                        if (Math.abs(now - t) > 60000) {
                            lock.delete();
                            continue;
                        }
                        if (!anyKeyMatches(keyCode)) {
                            lock.delete();
                            continue;
                        }
                        lastLocalClick = now;
                        Activity act = currentActivity.get();
                        if (act != null) {
                            for (XConfig.Profile p : cfg.profiles) {
                                if (!p.matchesPackage(pkg) || !p.matchesKey(keyCode)) continue;
                                try {
                                    trigger(p, act, lp);
                                } catch (Throwable t2) {
                                    XposedBridge.log("[XClick] 触发异常 " + t2);
                                }
                            }
                        }
                        lock.delete();
                    } catch (Throwable t) {
                    }
                }
            }
        }).start();
    }

    private boolean trigger(final XConfig.Profile p, Activity activity, XC_LoadPackage.LoadPackageParam lpparam) {
        if (activity == null) return false;
        activityStopped = false;
        clickTime = System.currentTimeMillis();
        final Activity clickAct = activity;
        View root = activity.getWindow().getDecorView();
        if (root == null) return false;
        List<View> candidates = collectCandidates(p, root, lpparam);
        if (candidates.isEmpty()) {
            XposedBridge.log("[XClick] [" + p.name + "] 未找到目标 view=" + p.viewId
                    + " child=" + p.childText);
            return false;
        }
        for (int i = 0; i < candidates.size() && i < 5; i++) {
            View c = candidates.get(i);
            String txt = "";
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                txt = cs == null ? "" : cs.toString();
            }
            if (txt.length() > 30) txt = txt.substring(0, 30);
            XposedBridge.log("[XClick] [" + p.name + "] 候选" + i + " "
                    + c.getClass().getName() + " shown=" + c.isShown() + " text=" + txt);
        }
        View pick = pick(candidates, activity, p.childRegex != null);
        if (pick == null) {
            XposedBridge.log("[XClick] [" + p.name + "] 无可视候选(候选均在屏幕可视区外),不点击");
            return false;
        }
        View decor = activity.getWindow().getDecorView();
        final View clicked = pick;
        final String beforeText = textOf(pick);
        boolean ok = triggerClick(pick, decor instanceof ViewGroup ? (ViewGroup) decor : null, p);
        XposedBridge.log("[XClick] [" + p.name + "] 选定候选" + candidates.indexOf(pick)
                + " clickable=" + pick.isClickable() + " onClickListener=" + hasClickListener(pick)
                + " 点击结果=" + ok);
        final ViewGroup froot = decor instanceof ViewGroup ? (ViewGroup) decor : null;
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                    }
                    String after = textOf(clicked);
                    XposedBridge.log("[XClick] [" + p.name + "] 点击前文本=" + beforeText
                            + " 点击后文本=" + after);
                    if (after.equals(beforeText) && clicked.isShown() && froot != null
                            && !activityStopped && currentActivity.get() == clickAct
                            && clickTime >= lastUserKey && clickTime >= lastUserTouch) {
                        try {
                            final View cv = clicked;
                            froot.post(new Runnable() {
                                @Override
                                public void run() {
                                    boolean ok2 = dispatchTouch(froot, cv);
                                    XposedBridge.log("[XClick] [" + p.name + "] 未展开,兜底点击视图中心=" + ok2);
                                }
                            });
                        } catch (Throwable t2) {
                        }
                    }
                }
            }).start();
        } catch (Throwable t) {
        }
        return ok;
    }

    private static String textOf(View v) {
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                return cs == null ? "" : cs.toString();
            }
        } catch (Throwable t) {
        }
        return "";
    }

    private static boolean hasClickListener(View v) {
        try {
            java.lang.reflect.Field f = View.class.getDeclaredField("mOnClickListener");
            f.setAccessible(true);
            return f.get(v) != null;
        } catch (Throwable t) {
            return true;
        }
    }

    private List<View> collectCandidates(XConfig.Profile p, View root, XC_LoadPackage.LoadPackageParam lpparam) {
        List<View> out = new ArrayList<View>();
        int rid = 0;
        if (p.viewId != null && !p.viewId.isEmpty()) {
            rid = resolveId(p.viewId, root, lpparam);
            if (rid == 0) return out;
        }
        ArrayDeque<View> stack = new ArrayDeque<View>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (rid != 0) {
                if (v.getId() == rid) {
                    if (p.childRegex != null) {
                        if (v instanceof ViewGroup) {
                            View child = findChildByText((ViewGroup) v, p.childRegex);
                            if (child != null && !out.contains(child)) out.add(child);
                        } else if (v instanceof TextView) {
                            CharSequence cs = ((TextView) v).getText();
                            if (cs != null && p.childRegex.matcher(cs.toString()).find()
                                    && !out.contains(v)) {
                                out.add(v);
                            }
                        }
                    } else if (!out.contains(v)) {
                        out.add(v);
                    }
                }
            } else if (p.childRegex != null && v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && p.childRegex.matcher(cs.toString()).find()
                        && !out.contains(v)) {
                    out.add(v);
                }
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = g.getChildCount() - 1; i >= 0; i--) {
                    stack.push(g.getChildAt(i));
                }
            }
        }
        return out;
    }

    private View findChildByText(ViewGroup g, java.util.regex.Pattern p) {
        ArrayDeque<View> stack = new ArrayDeque<View>();
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            stack.push(g.getChildAt(i));
        }
        while (!stack.isEmpty()) {
            View c = stack.pop();
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null && p.matcher(cs.toString()).find()) return c;
            }
            if (c instanceof ViewGroup) {
                ViewGroup cg = (ViewGroup) c;
                for (int i = cg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(cg.getChildAt(i));
                }
            }
        }
        return null;
    }

    private int resolveId(String name, View v, XC_LoadPackage.LoadPackageParam lpparam) {
        Integer cached = resIdCache.get(name);
        if (cached != null) return cached;
        int rid = 0;
        try {
            rid = v.getResources().getIdentifier(name, "id", lpparam.packageName);
        } catch (Throwable t) {
        }
        resIdCache.put(name, rid);
        return rid;
    }

    private View pick(List<View> candidates, Activity activity, boolean requireVisible) {
        List<View> shown = new ArrayList<View>();
        int sw = 0;
        int sh = 0;
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            sw = dm.widthPixels;
            sh = dm.heightPixels;
        } catch (Throwable t) {
        }
        for (View v : candidates) {
            if (!v.isShown()) continue;
            if (sw > 0 && sh > 0) {
                int[] pos = new int[2];
                try {
                    v.getLocationOnScreen(pos);
                } catch (Throwable t) {
                    continue;
                }
                if (pos[0] >= 0 && pos[1] >= 0
                        && pos[0] + v.getWidth() <= sw && pos[1] + v.getHeight() <= sh) {
                    shown.add(v);
                }
            } else {
                shown.add(v);
            }
        }
        if (requireVisible && shown.isEmpty()) return null;
        List<View> pool = shown.isEmpty() ? candidates : shown;
        if (pool.size() == 1) return pool.get(0);
        int cx = 0;
        int cy = 0;
        try {
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            cx = dm.widthPixels / 2;
            cy = dm.heightPixels / 2;
        } catch (Throwable t) {
        }
        View best = null;
        long bestDist = Long.MAX_VALUE;
        for (View v : pool) {
            int[] pos = new int[2];
            try {
                v.getLocationOnScreen(pos);
            } catch (Throwable t) {
                continue;
            }
            long dx = pos[0] + v.getWidth() / 2 - cx;
            long dy = pos[1] + v.getHeight() / 2 - cy;
            long d = dx * dx + dy * dy;
            if (d < bestDist) {
                bestDist = d;
                best = v;
            }
        }
        return best != null ? best : pool.get(0);
    }

    private boolean triggerClick(View v, ViewGroup root, XConfig.Profile p) {
        int[] xy = new int[2];
        if (v.isShown() && root != null && tapTextPoint(v, p, xy)) {
            if (dispatchTouchAt(root, xy[0], xy[1])) {
                int[] vr = new int[2];
                String vrstr = "";
                try {
                    v.getLocationOnScreen(vr);
                    vrstr = " 视图(" + vr[0] + "," + vr[1] + "," + (vr[0] + v.getWidth())
                            + "," + (vr[1] + v.getHeight()) + ")";
                } catch (Throwable t) {
                }
                XposedBridge.log("[XClick] 点击方式: 精准坐标触摸(" + xy[0] + "," + xy[1] + ")" + vrstr);
                return true;
            }
        }
        if (clickSpan(v, p)) {
            XposedBridge.log("[XClick] 点击方式: span");
            return true;
        }
        if (v.isShown() && root != null) {
            if (dispatchTouch(root, v)) {
                XposedBridge.log("[XClick] 点击方式: 真实触摸(中心)");
                return true;
            }
        }
        try {
            v.performClick();
            XposedBridge.log("[XClick] 点击方式: performClick");
            return true;
        } catch (Throwable t) {
        }
        try {
            v.callOnClick();
            XposedBridge.log("[XClick] 点击方式: callOnClick");
            return true;
        } catch (Throwable t) {
        }
        if (root != null && dispatchTouch(root, v)) {
            XposedBridge.log("[XClick] 点击方式: 触摸兜底");
            return true;
        }
        View cur = v;
        for (int depth = 0; cur != null && depth < 2; depth++) {
            if (cur.getParent() instanceof View) {
                View parent = (View) cur.getParent();
                try {
                    parent.performClick();
                    XposedBridge.log("[XClick] 点击方式: 父级performClick");
                    return true;
                } catch (Throwable t) {
                }
                cur = parent;
            } else {
                break;
            }
        }
        return false;
    }

    private boolean tapTextPoint(View v, XConfig.Profile p, int[] outXY) {
        try {
            if (!(v instanceof TextView)) return false;
            TextView tv = (TextView) v;
            CharSequence cs = tv.getText();
            if (cs == null) return false;
            String text = cs.toString();
            if (text.length() == 0) return false;
            int start = -1;
            int end = -1;
            if (cs instanceof android.text.Spanned) {
                android.text.Spanned sp = (android.text.Spanned) cs;
                android.text.style.ClickableSpan[] spans =
                        sp.getSpans(0, sp.length(), android.text.style.ClickableSpan.class);
                if (spans != null) {
                    for (android.text.style.ClickableSpan span : spans) {
                        int s = sp.getSpanStart(span);
                        int e = sp.getSpanEnd(span);
                        String sub = text.substring(Math.max(0, s), Math.min(e, text.length()));
                        if (p.childRegex != null
                                && sub.matches(".*" + p.childRegex.pattern() + ".*")) {
                            start = s;
                            end = e;
                            break;
                        }
                        if (start < 0) {
                            start = s;
                            end = e;
                        }
                    }
                }
            }
            if (start < 0 && p.childRegex != null) {
                java.util.regex.Matcher m = p.childRegex.matcher(text);
                if (m.find()) {
                    start = m.start();
                    end = m.end();
                }
            }
            if (start < 0 || end <= start) return false;
            android.text.Layout layout = tv.getLayout();
            if (layout == null) return false;
            int line = layout.getLineForOffset(start);
            float x = (layout.getPrimaryHorizontal(start) + layout.getPrimaryHorizontal(end)) / 2f;
            float y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            outXY[0] = Math.round(loc[0] + tv.getCompoundPaddingLeft() + x);
            outXY[1] = Math.round(loc[1] + tv.getCompoundPaddingTop() + y);
            if (outXY[0] < loc[0] || outXY[1] < loc[1]
                    || outXY[0] > loc[0] + v.getWidth() || outXY[1] > loc[1] + v.getHeight()) {
                return false;
            }
            try {
                android.util.DisplayMetrics dm = tv.getResources().getDisplayMetrics();
                if (outXY[0] < 0 || outXY[1] < 0
                        || outXY[0] > dm.widthPixels || outXY[1] > dm.heightPixels) {
                    return false;
                }
            } catch (Throwable t2) {
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean clickSpan(View v, XConfig.Profile p) {
        try {
            if (!(v instanceof TextView)) return false;
            TextView tv = (TextView) v;
            CharSequence cs = tv.getText();
            if (!(cs instanceof android.text.Spanned)) return false;
            android.text.Spanned sp = (android.text.Spanned) cs;
            int end = sp.length();
            if (end <= 0) return false;
            android.text.style.ClickableSpan[] spans =
                    sp.getSpans(0, end, android.text.style.ClickableSpan.class);
            if (spans == null || spans.length == 0) return false;
            String text = cs.toString();
            int matchStart = -1;
            if (p.childRegex != null) {
                java.util.regex.Matcher m = p.childRegex.matcher(text);
                if (m.find()) matchStart = m.start();
            }
            android.text.style.ClickableSpan target = null;
            if (matchStart >= 0) {
                for (android.text.style.ClickableSpan span : spans) {
                    int s = sp.getSpanStart(span);
                    int e = sp.getSpanEnd(span);
                    if (s <= matchStart && matchStart < e) {
                        target = span;
                        break;
                    }
                }
            }
            if (target == null) {
                int best = -1;
                for (int i = 0; i < spans.length; i++) {
                    if (sp.getSpanStart(spans[i]) >= best) {
                        best = sp.getSpanStart(spans[i]);
                        target = spans[i];
                    }
                }
            }
            if (target != null) {
                target.onClick(v);
                return true;
            }
        } catch (Throwable t) {
        }
        return false;
    }

    private boolean dispatchTouch(ViewGroup root, View v) {
        try {
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            return dispatchTouchAt(root, loc[0] + v.getWidth() / 2f, loc[1] + v.getHeight() / 2f);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean dispatchTouchAt(ViewGroup root, float x, float y) {
        try {
            int[] decLoc = new int[2];
            root.getLocationOnScreen(decLoc);
            x -= decLoc[0];
            y -= decLoc[1];
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
            down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            root.dispatchTouchEvent(down);
            down.recycle();
            MotionEvent up = MotionEvent.obtain(t + 80, t + 80, MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            root.dispatchTouchEvent(up);
            up.recycle();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}