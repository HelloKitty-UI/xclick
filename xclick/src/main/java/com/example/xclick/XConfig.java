package com.example.xclick;

import android.view.KeyEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public class XConfig {

    public int keyCode = KeyEvent.KEYCODE_VOLUME_UP;
    public String keyName = "VOLUME_UP";
    public Set<String> packages = new HashSet<String>();
    public List<String> viewIds = new ArrayList<String>();
    public Pattern childRegex = null;
    public Pattern textRegex = null;
    public String pickMode = "closest";
    public boolean walkUp = true;
    public boolean consumeKey = true;
    public long debounceMs = 600;
    public String rawText = "";
    public String source = "";

    public boolean matchesPackage(String pkg) {
        if (packages.isEmpty()) return true;
        return packages.contains(pkg);
    }

    public static String template() {
        return "# 通用按键点击器配置 (修改后保存即可，无需重启手机；重启目标应用生效)\n"
                + "# 生效的应用包名(逗号分隔)，留空=所有应用\n"
                + "packages=com.bilibili.app.in\n"
                + "# 触发按键: VOLUME_UP / VOLUME_DOWN / DPAD_UP / DPAD_DOWN / DPAD_LEFT / DPAD_RIGHT / DPAD_CENTER / ENTER / BACK / PAGE_UP / PAGE_DOWN / SPACE / F1~F12 / KEYCODE_CAMERA\n"
                + "keys=VOLUME_UP\n"
                + "# 要点击的view id(逗号分隔，可多个，取匹配中最近的)\n"
                + "view_id=plugin_comment_widget\n"
                + "# 可选: 在匹配view内部查找文本命中的子view（留空=点view本身）\n"
                + "#child_text_regex=共[0-9,，.万wW]+条回复\n"
                + "# 可选: 不填view_id时，直接按文本匹配所有TextView\n"
                + "#text_regex=共[0-9,，.万wW]+条回复\n"
                + "# 匹配到多个时选哪个: closest=离屏幕中心最近 top=最靠上 first=找到的第一个\n"
                + "pick_mode=closest\n"
                + "# 点击成功后是否消费掉按键(0=不消费，1=消费，音量键不再弹音量条)\n"
                + "consume_key=1\n"
                + "# 点击目标无响应时是否向上找可点击父级(0/1)\n"
                + "walk_up_parent=1\n"
                + "# 防抖毫秒\n"
                + "debounce_ms=600";
    }

    public static int parseKey(String name) {
        if (name == null || name.trim().isEmpty()) return KeyEvent.KEYCODE_VOLUME_UP;
        String n = name.trim().toUpperCase();
        if (n.startsWith("KEYCODE_")) n = n.substring(8);
        if (n.equals("VOLUME_UP")) return KeyEvent.KEYCODE_VOLUME_UP;
        if (n.equals("VOLUME_DOWN")) return KeyEvent.KEYCODE_VOLUME_DOWN;
        if (n.equals("DPAD_UP")) return KeyEvent.KEYCODE_DPAD_UP;
        if (n.equals("DPAD_DOWN")) return KeyEvent.KEYCODE_DPAD_DOWN;
        if (n.equals("DPAD_LEFT")) return KeyEvent.KEYCODE_DPAD_LEFT;
        if (n.equals("DPAD_RIGHT")) return KeyEvent.KEYCODE_DPAD_RIGHT;
        if (n.equals("DPAD_CENTER")) return KeyEvent.KEYCODE_DPAD_CENTER;
        if (n.equals("ENTER")) return KeyEvent.KEYCODE_ENTER;
        if (n.equals("BACK")) return KeyEvent.KEYCODE_BACK;
        if (n.equals("PAGE_UP")) return KeyEvent.KEYCODE_PAGE_UP;
        if (n.equals("PAGE_DOWN")) return KeyEvent.KEYCODE_PAGE_DOWN;
        if (n.equals("SPACE")) return KeyEvent.KEYCODE_SPACE;
        if (n.equals("CAMERA")) return KeyEvent.KEYCODE_CAMERA;
        try {
            java.lang.reflect.Field f = KeyEvent.class.getField("KEYCODE_" + n);
            return f.getInt(null);
        } catch (Throwable t) {
            return KeyEvent.KEYCODE_VOLUME_UP;
        }
    }

    public static XConfig parse(String text) {
        XConfig c = new XConfig();
        if (text == null) return c;
        c.rawText = text;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            String v = t.substring(eq + 1).trim();
            if (k.equals("keys")) {
                c.keyName = v;
                c.keyCode = parseKey(v);
            } else if (k.equals("packages")) {
                for (String p : v.split(",")) {
                    String pp = p.trim();
                    if (!pp.isEmpty()) c.packages.add(pp);
                }
            } else if (k.equals("view_id")) {
                for (String p : v.split(",")) {
                    String pp = p.trim();
                    if (!pp.isEmpty()) c.viewIds.add(pp);
                }
            } else if (k.equals("child_text_regex")) {
                if (!v.isEmpty()) c.childRegex = Pattern.compile(v);
            } else if (k.equals("text_regex")) {
                if (!v.isEmpty()) c.textRegex = Pattern.compile(v);
            } else if (k.equals("pick_mode")) {
                c.pickMode = v;
            } else if (k.equals("walk_up_parent")) {
                c.walkUp = !v.equals("0");
            } else if (k.equals("consume_key")) {
                c.consumeKey = !v.equals("0");
            } else if (k.equals("debounce_ms")) {
                try {
                    c.debounceMs = Long.parseLong(v);
                } catch (Exception e) {
                }
            }
        }
        return c;
    }

    public static String readFile(File f) {
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), 65536)];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }
}
