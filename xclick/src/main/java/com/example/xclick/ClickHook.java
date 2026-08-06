package com.example.xclick;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
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

    private long lastTrigger = 0;
    private XConfig cfg;
    private boolean cfgLoadedOnce = false;
    private long cfgFileStamp = -1;
    private final Map<String, Integer> resIdCache = new HashMap<String, Integer>();
    private WeakReference<Activity> currentActivity = new WeakReference<Activity>(null);

    private static XConfig tryFromFile(String path) {
        if (path == null) return null;
        String text = XConfig.readFile(new File(path));
        if (text == null || text.trim().isEmpty()) return null;
        XConfig c = XConfig.parse(text);
        c.source = path;
        return c;
    }

    private static XConfig tryFromPrefs() {
        try {
            de.robv.android.xposed.XSharedPreferences p =
                    new de.robv.android.xposed.XSharedPreferences("com.example.xclick", CONFIG_PREFS);
            p.makeWorldReadable();
            if (p.getFile() == null || !p.getFile().canRead()) return null;
            String text = p.getString(CONFIG_KEY, "");
            if (text == null || text.trim().isEmpty()) return null;
            XConfig c = XConfig.parse(text);
            c.source = "prefs";
            return c;
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
            c.source = "default";
        }
        return c;
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals("com.example.xclick")) return;
        try {
            cfg = loadConfig();
            if (!cfg.matchesPackage(lpparam.packageName)) return;
            if (!cfgLoadedOnce) {
                cfgLoadedOnce = true;
                XposedBridge.log("[XClick] 配置源: " + cfg.source
                        + " | 按键: " + cfg.keyName
                        + " | view_id: " + cfg.viewIds
                        + " | packages: " + cfg.packages);
            }
        } catch (Throwable t) {
            XposedBridge.log("[XClick] 配置加载失败 " + t);
            return;
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
                            if (event.getKeyCode() != cfg.keyCode) return;
                            if (!cfg.source.equals("prefs") && !cfg.source.equals("default")) {
                                try {
                                    XConfig fresh = loadConfig();
                                    if (fresh != null && !fresh.source.equals("default")) {
                                        cfg = fresh;
                                    }
                                } catch (Throwable t) {
                                }
                            }
                            long now = System.currentTimeMillis();
                            if (now - lastTrigger < cfg.debounceMs) return;
                            lastTrigger = now;
                            if (trigger((Activity) act, lpparam)) {
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

    private boolean trigger(Activity activity, XC_LoadPackage.LoadPackageParam lpparam) {
        if (activity == null) return false;
        try {
            View root = activity.getWindow().getDecorView();
            if (root == null) return false;
            List<View> targets = collectTargets(root, lpparam);
            if (targets.isEmpty()) {
                XposedBridge.log("[XClick] 未找到匹配view (view_id=" + cfg.viewIds
                        + " text_regex=" + cfg.textRegex + " child_regex=" + cfg.childRegex + ")");
                return false;
            }
            View pick = pick(targets, activity);
            boolean ok = triggerClick(pick);
            XposedBridge.log("[XClick] 候选=" + targets.size() + " 目标=" + pick.getClass().getName()
                    + " 点击结果=" + ok);
            return ok;
        } catch (Throwable t) {
            XposedBridge.log("[XClick] 触发异常 " + t);
            return false;
        }
    }

    private List<View> collectTargets(View root, XC_LoadPackage.LoadPackageParam lpparam) {
        List<View> out = new ArrayList<View>();
        ArrayDeque<View> stack = new ArrayDeque<View>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (!v.isShown()) continue;
            if (!cfg.viewIds.isEmpty()) {
                int rid = resolveId(v, lpparam);
                if (rid != 0 && v.getId() == rid) {
                    if (cfg.childRegex != null && v instanceof ViewGroup) {
                        View child = findChildByText((ViewGroup) v, cfg.childRegex);
                        if (child != null) out.add(child);
                    } else {
                        out.add(v);
                    }
                }
            } else if (cfg.textRegex != null && v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs != null && cfg.textRegex.matcher(cs.toString()).find()) {
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

    private int resolveId(View v, XC_LoadPackage.LoadPackageParam lpparam) {
        for (String name : cfg.viewIds) {
            Integer cached = resIdCache.get(name);
            if (cached != null) return cached;
        }
        int rid = 0;
        for (String name : cfg.viewIds) {
            try {
                int id = v.getResources().getIdentifier(name, "id", lpparam.packageName);
                resIdCache.put(name, id);
                if (id != 0) rid = id;
            } catch (Throwable t) {
            }
        }
        return rid;
    }

    private View pick(List<View> targets, Activity activity) {
        if (targets.size() == 1) return targets.get(0);
        if (cfg.pickMode.equals("first")) return targets.get(0);
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
        int bestTop = Integer.MAX_VALUE;
        int bestX = Integer.MAX_VALUE;
        for (View v : targets) {
            int[] pos = new int[2];
            try {
                v.getLocationOnScreen(pos);
            } catch (Throwable t) {
                continue;
            }
            if (cfg.pickMode.equals("top")) {
                if (pos[1] < bestTop || (pos[1] == bestTop && pos[0] < bestX)) {
                    bestTop = pos[1];
                    bestX = pos[0];
                    best = v;
                }
            } else {
                long dx = pos[0] + v.getWidth() / 2 - cx;
                long dy = pos[1] + v.getHeight() / 2 - cy;
                long d = dx * dx + dy * dy;
                if (d < bestDist) {
                    bestDist = d;
                    best = v;
                }
            }
        }
        return best != null ? best : targets.get(0);
    }

    private boolean triggerClick(View v) {
        if (v.isClickable()) {
            try {
                v.performClick();
                return true;
            } catch (Throwable t) {
            }
        }
        try {
            if (v instanceof TextView) {
                CharSequence cs = ((TextView) v).getText();
                if (cs instanceof android.text.Spanned) {
                    android.text.Spanned sp = (android.text.Spanned) cs;
                    int end = sp.length();
                    if (end > 0) {
                        android.text.style.ClickableSpan[] spans =
                                sp.getSpans(0, end, android.text.style.ClickableSpan.class);
                        if (spans != null && spans.length > 0) {
                            spans[0].onClick(v);
                            return true;
                        }
                    }
                }
            }
        } catch (Throwable t) {
        }
        if (cfg.walkUp) {
            View cur = v;
            for (int depth = 0; cur != null && depth < 3; depth++) {
                if (cur.getParent() instanceof View) {
                    View p = (View) cur.getParent();
                    if (p.isClickable()) {
                        try {
                            p.performClick();
                            return true;
                        } catch (Throwable t) {
                        }
                    }
                    cur = p;
                } else {
                    break;
                }
            }
        }
        return false;
    }
}
