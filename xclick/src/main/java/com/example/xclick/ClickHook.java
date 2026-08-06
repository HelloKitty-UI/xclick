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

    private long lastTrigger = 0;
    private XConfig cfg;
    private boolean cfgLoadedOnce = false;
    private final Map<String, Integer> resIdCache = new HashMap<String, Integer>();
    private WeakReference<Activity> currentActivity = new WeakReference<Activity>(null);

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
                            boolean keyWanted = false;
                            for (XConfig.Profile p : cfg.profiles) {
                                if (p.matchesKey(event.getKeyCode())) {
                                    keyWanted = true;
                                    break;
                                }
                            }
                            if (!keyWanted) return;
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
                            if (handled) {
                                if (cfg.consumeKey) {
                                    param.setResult(true);
                                }
                            } else {
                                lastTrigger = 0;
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[XClick] hook 异常 " + t);
                        }
                    }
                });
    }

    private boolean trigger(final XConfig.Profile p, Activity activity, XC_LoadPackage.LoadPackageParam lpparam) {
        if (activity == null) return false;
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
        View pick = pick(candidates, activity);
        View decor = activity.getWindow().getDecorView();
        final View clicked = pick;
        final String beforeText = textOf(pick);
        boolean ok = triggerClick(pick, decor instanceof ViewGroup ? (ViewGroup) decor : null, p);
        XposedBridge.log("[XClick] [" + p.name + "] 选定候选" + candidates.indexOf(pick)
                + " clickable=" + pick.isClickable() + " onClickListener=" + hasClickListener(pick)
                + " 点击结果=" + ok);
        try {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e) {
                    }
                    XposedBridge.log("[XClick] [" + p.name + "] 点击前文本=" + beforeText
                            + " 点击后文本=" + textOf(clicked));
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
                    if (p.childRegex != null && v instanceof ViewGroup) {
                        View child = findChildByText((ViewGroup) v, p.childRegex);
                        if (child != null) out.add(child);
                    } else {
                        out.add(v);
                    }
                }
            } else if (p.childRegex != null && v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && p.childRegex.matcher(cs.toString()).find()) {
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
        View found = null;
        int last = -1;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c instanceof TextView) {
                CharSequence cs = ((TextView) c).getText();
                if (cs != null && p.matcher(cs.toString()).find()) {
                    if (found == null || i > last) {
                        found = c;
                        last = i;
                    }
                }
            }
        }
        if (found != null) return found;
        if (g.getChildCount() > 0) return g.getChildAt(g.getChildCount() - 1);
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

    private View pick(List<View> candidates, Activity activity) {
        List<View> shown = new ArrayList<View>();
        for (View v : candidates) {
            if (v.isShown()) shown.add(v);
        }
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
                XposedBridge.log("[XClick] 点击方式: 精准坐标触摸(命中文字)");
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
            int offset = -1;
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
                            offset = s;
                            break;
                        }
                        if (offset < 0) offset = s;
                    }
                }
            }
            if (offset < 0 && p.childRegex != null) {
                java.util.regex.Matcher m = p.childRegex.matcher(text);
                if (m.find()) offset = m.start();
            }
            if (offset < 0 || offset >= text.length()) return false;
            android.text.Layout layout = tv.getLayout();
            if (layout == null) return false;
            int line = layout.getLineForOffset(offset);
            float x = layout.getPrimaryHorizontal(offset) + 2;
            float y = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            outXY[0] = Math.round(loc[0] + tv.getPaddingLeft() + x);
            outXY[1] = Math.round(loc[1] + tv.getPaddingTop() + y);
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