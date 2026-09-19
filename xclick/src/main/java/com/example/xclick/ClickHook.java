package com.example.xclick;

import android.app.Activity;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ClickHook extends XposedModule {

    private static final String CONFIG_PREFS = "xclick_config";
    private static final String CONFIG_KEY = "config";

    private long lastTrigger = 0;
    private long lastKeyWrite = 0;
    private long lastLocalClick = 0;
    private volatile boolean watcherStarted = false;
    private volatile boolean activityStopped = false;
    private volatile long clickTime = 0;
    private volatile long lastUserKey = 0;
    private volatile long lastUserTouch = 0;
    private String pkg;
    private ClassLoader pkgClassLoader;
    private android.content.pm.ApplicationInfo pkgAppInfo;
    private XConfig cfg;
    private final Map<String, Integer> resIdCache = new HashMap<String, Integer>();
    private WeakReference<Activity> currentActivity = new WeakReference<Activity>(null);
    private String triggerPath = null;

    private static long flagCacheAt = 0;
    private static boolean flagCacheVal = false;
    private static boolean btInputConnected = false;
    private static final int BT_PROFILE_HID_HOST = 4;
    private static final String BT_HID_CONNECTION_STATE_CHANGED =
            "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED";

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        String packageName = param.getPackageName();
        ClassLoader classLoader = param.getDefaultClassLoader();
        android.content.pm.ApplicationInfo appInfo = param.getApplicationInfo();

        if ("android".equals(packageName)) {
            hookSystemDisplayRotation(classLoader);
            hookBtAutomation(classLoader);
            return;
        }
        if ("com.example.xclick".equals(packageName)) return;

        try {
            cfg = loadConfig();
        } catch (Throwable t) {
            return;
        }
        boolean anyMatch = false;
        for (XConfig.Profile p : cfg.profiles) {
            if (p.matchesPackage(packageName)) {
                anyMatch = true;
                break;
            }
        }
        if (!anyMatch) return;

        pkg = packageName;
        pkgClassLoader = classLoader;
        pkgAppInfo = appInfo;
        try {
            triggerPath = appInfo.dataDir + "/files/xclick_trigger.txt";
        } catch (Throwable t) {
        }

        hookDispatchKeyEvent();
        hookOnResume();
        hookOnStop();
        hookDispatchTouchEvent();
        hookOnBackPressed();
        hookMediaSessionCallback();
        hookBiliSearchTabs(classLoader);
        startWatcher();
    }

    private void hookSystemDisplayRotation(ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName("com.android.server.wm.DisplayRotation", false, classLoader);
            Method m = clazz.getDeclaredMethod("rotationForOrientation", int.class, int.class);
            this.hook(m).intercept(chain -> {
                boolean enabled;
                try {
                    enabled = isRotate270Enabled();
                } catch (Throwable t) {
                    enabled = false;
                }
                int orientation = ((Integer) chain.getArg(0)).intValue();
                int rotation = ((Integer) chain.proceed()).intValue();
                if (enabled && rotation == android.view.Surface.ROTATION_90
                        && isLandscapeOrientation(orientation)) {
                    return android.view.Surface.ROTATION_270;
                }
                return rotation;
            });
        } catch (Throwable t) {
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

    private static boolean isRotate270Enabled() {
        long now = SystemClock.elapsedRealtime();
        if (now - flagCacheAt < 2000) return flagCacheVal;
        String text = XConfig.readFile(new File("/data/data/com.example.xclick/files/xclick.conf"));
        if (text == null || text.trim().isEmpty()) {
            text = readPrefsFile();
        }
        flagCacheAt = now;
        boolean manual = parseRotate270Flag(text);
        boolean btAuto = parseBtAutoFlag(text);
        flagCacheVal = manual || (btAuto && btInputConnected);
        return flagCacheVal;
    }

    private static String readPrefsFile() {
        try {
            File f = new File("/data/user_de/0/com.example.xclick/shared_prefs/" + CONFIG_PREFS + ".xml");
            if (!f.exists()) f = new File("/data/data/com.example.xclick/shared_prefs/" + CONFIG_PREFS + ".xml");
            if (!f.canRead()) {
                f = new File("/data/user/0/com.example.xclick/shared_prefs/" + CONFIG_PREFS + ".xml");
            }
            if (!f.canRead()) return null;
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean inConfig = false;
            while ((line = r.readLine()) != null) {
                if (line.contains(CONFIG_KEY)) {
                    inConfig = true;
                    int start = line.indexOf('>');
                    int end = line.lastIndexOf('<');
                    if (start >= 0 && end > start) {
                        sb.append(line.substring(start + 1, end));
                    }
                } else if (inConfig && line.contains("</string")) {
                    inConfig = false;
                } else if (inConfig) {
                    sb.append(line.trim());
                }
            }
            r.close();
            String val = sb.toString();
            return val.isEmpty() ? null : val;
        } catch (Throwable t) {
            return null;
        }
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

    private static boolean parseBtAutoFlag(String text) {
        if (text == null) return false;
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            if (k.equals("bt_rotate_auto") || k.equals("btrotateauto")) {
                return !t.substring(eq + 1).trim().equals("0");
            }
        }
        return false;
    }

    private void hookBtAutomation(ClassLoader classLoader) {
        try {
            Class<?> appCls = Class.forName("android.app.Application", false, classLoader);
            Method m = appCls.getDeclaredMethod("onCreate");
            this.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                registerBtReceiver((android.content.Context) chain.getThisObject());
                return result;
            });
        } catch (Throwable t) {
        }
    }

    private static void registerBtReceiver(final android.content.Context ctx) {
        try {
            android.content.BroadcastReceiver r = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(android.content.Context c, android.content.Intent it) {
                    String act = it.getAction();
                    boolean conn = false;
                    boolean disc = false;
                    if (act != null) {
                        if (act.equals(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED)) {
                            conn = true;
                        } else if (act.equals(
                                android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED)) {
                            disc = true;
                        } else if (act.equals(BT_HID_CONNECTION_STATE_CHANGED)) {
                            int st = it.getIntExtra("android.bluetooth.profile.extra.STATE", -1);
                            if (st == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                                conn = true;
                            } else if (st == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                                disc = true;
                            } else {
                                conn = true;
                            }
                        }
                    }
                    if (conn) {
                        updateBtInputState();
                        scheduleBtCheck(800);
                    } else if (disc) {
                        scheduleBtCheck(300);
                    }
                }
            };
            android.content.IntentFilter f = new android.content.IntentFilter();
            f.addAction(BT_HID_CONNECTION_STATE_CHANGED);
            f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_CONNECTED);
            f.addAction(android.bluetooth.BluetoothDevice.ACTION_ACL_DISCONNECTED);
            try {
                ctx.registerReceiver(r, f);
            } catch (Throwable t) {
            }
            updateBtInputState();
        } catch (Throwable t) {
        }
    }

    private static void scheduleBtCheck(long delay) {
        try {
            final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    updateBtInputState();
                }
            }, delay);
        } catch (Throwable t) {
        }
    }

    private static void updateBtInputState() {
        try {
            boolean has;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                has = hasBluetoothInputDeviceByInputManager();
            } else {
                android.bluetooth.BluetoothAdapter a =
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                has = a != null
                        && a.getProfileConnectionState(BT_PROFILE_HID_HOST)
                        == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
            }
            if (has != btInputConnected) {
                btInputConnected = has;
                flagCacheAt = 0;
            }
        } catch (Throwable t) {
        }
    }

    private static boolean hasBluetoothInputDeviceByInputManager() {
        try {
            Class<?> imCls = Class.forName("android.hardware.input.InputManager");
            Object im = imCls.getMethod("getInstance").invoke(null);
            int[] ids = (int[]) imCls.getMethod("getInputDeviceIds").invoke(im);
            if (ids == null) return false;
            java.lang.reflect.Method getDev = imCls.getMethod("getInputDevice", int.class);
            for (int id : ids) {
                Object dev = getDev.invoke(im, Integer.valueOf(id));
                if (dev == null) continue;
                Object addr = dev.getClass().getMethod("getBluetoothAddress").invoke(dev);
                if (addr != null && !((String) addr).isEmpty()) return true;
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private void hookDispatchKeyEvent() {
        try {
            Method m = Activity.class.getDeclaredMethod("dispatchKeyEvent", KeyEvent.class);
            this.hook(m).intercept(chain -> {
                try {
                    Object act = chain.getThisObject();
                    if (act instanceof Activity) {
                        currentActivity = new WeakReference<Activity>((Activity) act);
                    }
                    KeyEvent event = (KeyEvent) chain.getArg(0);
                    if (event == null) return chain.proceed();
                    if (event.getAction() != KeyEvent.ACTION_DOWN) return chain.proceed();
                    lastUserKey = System.currentTimeMillis();
                    boolean pkgWanted = false;
                    for (XConfig.Profile p : cfg.profiles) {
                        if (p.matchesPackage(pkg) && p.matchesKey(event.getKeyCode())) {
                            pkgWanted = true;
                            break;
                        }
                    }
                    if (!pkgWanted) return chain.proceed();
                    try {
                        XConfig fresh = loadConfig();
                        if (fresh != null && !fresh.profiles.isEmpty()) {
                            cfg = fresh;
                        }
                    } catch (Throwable t) {
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastTrigger < cfg.debounceMs) return chain.proceed();
                    lastTrigger = now;
                    boolean handled = false;
                    Activity activity = (act instanceof Activity) ? (Activity) act : null;
                    for (XConfig.Profile p : cfg.profiles) {
                        if (!p.matchesPackage(pkg)) continue;
                        if (!p.matchesKey(event.getKeyCode())) continue;
                        try {
                            if (trigger(p, activity)) {
                                handled = true;
                            }
                        } catch (Throwable t) {
                        }
                    }
                    lastLocalClick = System.currentTimeMillis();
                    if (!handled) {
                        lastTrigger = 0;
                    }
                    writeKeyTrigger(event.getKeyCode());
                    if (cfg.consumeKey) {
                        return true;
                    }
                } catch (Throwable t) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
        }
    }

    private void hookOnResume() {
        try {
            Method m = Activity.class.getDeclaredMethod("onResume");
            this.hook(m).intercept(chain -> {
                Object result = chain.proceed();
                try {
                    Object thiz = chain.getThisObject();
                    if (thiz instanceof Activity) {
                        currentActivity = new WeakReference<Activity>((Activity) thiz);
                    }
                } catch (Throwable t) {
                }
                return result;
            });
        } catch (Throwable t) {
        }
    }

    private void hookOnStop() {
        try {
            Method m = Activity.class.getDeclaredMethod("onStop");
            this.hook(m).intercept(chain -> {
                activityStopped = true;
                return chain.proceed();
            });
        } catch (Throwable t) {
        }
    }

    private void hookDispatchTouchEvent() {
        try {
            Method m = Activity.class.getDeclaredMethod("dispatchTouchEvent", MotionEvent.class);
            this.hook(m).intercept(chain -> {
                lastUserTouch = System.currentTimeMillis();
                return chain.proceed();
            });
        } catch (Throwable t) {
        }
    }

    private void hookOnBackPressed() {
        try {
            Method m = Activity.class.getDeclaredMethod("onBackPressed");
            this.hook(m).intercept(chain -> {
                lastUserKey = System.currentTimeMillis();
                return chain.proceed();
            });
        } catch (Throwable t) {
        }
    }

    private void hookMediaSessionCallback() {
        try {
            Class<?> cb = Class.forName("android.media.session.MediaSession$Callback", false, null);
            Method m = cb.getDeclaredMethod("onMediaButtonEvent", android.content.Intent.class);
            this.hook(m).intercept(chain -> {
                try {
                    android.content.Intent it = (android.content.Intent) chain.getArg(0);
                    KeyEvent ke = (KeyEvent) it.getParcelableExtra(
                            android.content.Intent.EXTRA_KEY_EVENT);
                    if (ke != null && ke.getAction() == KeyEvent.ACTION_DOWN) {
                        int code = ke.getKeyCode();
                        if (cfg != null && pkg != null && anyKeyMatches(code)) {
                            lastUserKey = System.currentTimeMillis();
                            return true;
                        }
                        writeKeyTrigger(code);
                    }
                } catch (Throwable t2) {
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
        }
    }

    private void hookBiliSearchTabs(ClassLoader cl) {
        try {
            Class<?> searchState = Class.forName(
                    "com.bilibili.search2.result.base.SearchState", false, cl);
            Class<?> navInfo = Class.forName(
                    "com.bilibili.search2.api.SearchResultAll$NavInfo", false, cl);

            Method getNav = searchState.getDeclaredMethod("getNav");
            this.hook(getNav).intercept(chain -> {
                try {
                    List<?> nav = (List<?>) chain.proceed();
                    return completeNav(nav, navInfo);
                } catch (Throwable ignored) {
                    return chain.proceed();
                }
            });

            Method setNav = searchState.getDeclaredMethod("setNav", List.class);
            this.hook(setNav).intercept(chain -> {
                try {
                    List<?> nav = (List<?>) chain.getArg(0);
                    chain.getArgs().set(0, completeNav(nav, navInfo));
                } catch (Throwable ignored) {
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {
        }
    }

    private static final int COLUMN_TYPE = 6;
    private static final String COLUMN_NAME = "\u4e13\u680f";

    private static List<?> completeNav(List<?> nav, Class<?> navInfo) {
        if (nav != null) {
            for (Object o : nav) {
                if (o == null) continue;
                try {
                    Method getType = o.getClass().getDeclaredMethod("getType");
                    Object type = getType.invoke(o);
                    if (type != null && ((Integer) type).intValue() == COLUMN_TYPE) {
                        return nav;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        List<Object> full = nav == null ? new ArrayList<Object>() : new ArrayList<Object>(nav);
        try {
            Object ni = navInfo.getDeclaredConstructor().newInstance();
            Method setName = navInfo.getDeclaredMethod("setName", String.class);
            setName.invoke(ni, COLUMN_NAME);
            Method setType = navInfo.getDeclaredMethod("setType", int.class);
            setType.invoke(ni, COLUMN_TYPE);
            Method setTotal = navInfo.getDeclaredMethod("setTotal", int.class);
            setTotal.invoke(ni, 0);
            Method setPages = navInfo.getDeclaredMethod("setPages", int.class);
            setPages.invoke(ni, 0);
            full.add(ni);
        } catch (Throwable t) {
        }
        return full;
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
                                    trigger(p, act);
                                } catch (Throwable t2) {
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

    private boolean trigger(final XConfig.Profile p, Activity activity) {
        if (activity == null) return false;
        activityStopped = false;
        clickTime = System.currentTimeMillis();
        final Activity clickAct = activity;
        View root = activity.getWindow().getDecorView();
        if (root == null) return false;
        List<View> candidates = collectCandidates(p, root);
        if (candidates.isEmpty()) {
            return false;
        }
        View pick = pick(candidates, activity, p.childRegex != null);
        if (pick == null) {
            return false;
        }
        View decor = activity.getWindow().getDecorView();
        final View clicked = pick;
        final String beforeText = textOf(pick);
        boolean ok = triggerClick(pick, decor instanceof ViewGroup ? (ViewGroup) decor : null, p);
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
                    if (after.equals(beforeText) && clicked.isShown() && froot != null
                            && !activityStopped && currentActivity.get() == clickAct
                            && clickTime >= lastUserKey && clickTime >= lastUserTouch) {
                        try {
                            final View cv = clicked;
                            froot.post(new Runnable() {
                                @Override
                                public void run() {
                                    dispatchTouch(froot, cv);
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

    private List<View> collectCandidates(XConfig.Profile p, View root) {
        List<View> out = new ArrayList<View>();
        int rid = 0;
        if (p.viewId != null && !p.viewId.isEmpty()) {
            rid = resolveId(p.viewId, root);
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
                            List<View> children = findChildrenByText((ViewGroup) v, p.childRegex);
                            for (View child : children) {
                                if (!out.contains(child)) out.add(child);
                            }
                        } else if (v instanceof TextView) {
                            CharSequence cs = ((TextView) v).getText();
                            if (cs != null && p.childRegex.matcher(cs.toString()).find()
                                    && !out.contains(v)) {
                                out.add(v);
                            }
                        }
                    } else if (v instanceof ViewGroup) {
                        List<View> tvs = findAllTextViews((ViewGroup) v);
                        for (View tv : tvs) {
                            if (!out.contains(tv)) out.add(tv);
                        }
                        if (tvs.isEmpty() && !out.contains(v)) {
                            out.add(v);
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

    private List<View> findChildrenByText(ViewGroup g, java.util.regex.Pattern p) {
        List<View> result = new ArrayList<View>();
        ArrayDeque<View> stack = new ArrayDeque<View>();
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            stack.push(g.getChildAt(i));
        }
        while (!stack.isEmpty()) {
            View c = stack.pop();
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null && p.matcher(cs.toString()).find() && !result.contains(c)) {
                    result.add(c);
                }
            }
            if (c instanceof ViewGroup) {
                ViewGroup cg = (ViewGroup) c;
                for (int i = cg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(cg.getChildAt(i));
                }
            }
        }
        return result;
    }

    private List<View> findAllTextViews(ViewGroup g) {
        List<View> result = new ArrayList<View>();
        ArrayDeque<View> stack = new ArrayDeque<View>();
        for (int i = g.getChildCount() - 1; i >= 0; i--) {
            stack.push(g.getChildAt(i));
        }
        while (!stack.isEmpty()) {
            View c = stack.pop();
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null && cs.length() > 0 && !result.contains(c)) {
                    result.add(c);
                }
            }
            if (c instanceof ViewGroup) {
                ViewGroup cg = (ViewGroup) c;
                for (int i = cg.getChildCount() - 1; i >= 0; i--) {
                    stack.push(cg.getChildAt(i));
                }
            }
        }
        return result;
    }

    private int resolveId(String name, View v) {
        Integer cached = resIdCache.get(name);
        if (cached != null) return cached;
        int rid = 0;
        try {
            rid = v.getResources().getIdentifier(name, "id", pkg);
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
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
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
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
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
                return true;
            }
        }
        if (clickSpan(v, p)) {
            return true;
        }
        if (v.isShown() && root != null) {
            if (dispatchTouch(root, v)) {
                return true;
            }
        }
        try {
            v.performClick();
            return true;
        } catch (Throwable t) {
        }
        try {
            v.callOnClick();
            return true;
        } catch (Throwable t) {
        }
        if (root != null && dispatchTouch(root, v)) {
            return true;
        }
        View cur = v;
        for (int depth = 0; cur != null && depth < 2; depth++) {
            if (cur.getParent() instanceof View) {
                View parent = (View) cur.getParent();
                try {
                    parent.performClick();
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

    private static XConfig tryFromFile(String path) {
        if (path == null) return null;
        String text = XConfig.readFile(new File(path));
        if (text == null || text.trim().isEmpty()) return null;
        return XConfig.parse(text);
    }

    private XConfig loadConfig() {
        XConfig c = tryFromFile("/data/user/0/com.example.xclick/files/xclick.conf");
        if (c == null) c = tryFromFile("/data/data/com.example.xclick/files/xclick.conf");
        if (c == null) {
            String prefs = readPrefsFile();
            if (prefs != null && !prefs.trim().isEmpty()) {
                c = XConfig.parse(prefs);
            }
        }
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
}
