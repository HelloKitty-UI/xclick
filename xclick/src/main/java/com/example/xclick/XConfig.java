package com.example.xclick;

import android.view.KeyEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class XConfig {

    public long debounceMs = 600;
    public boolean consumeKey = true;

    public static class Profile {
        public String name = "未命名";
        public String pkg = "";
        public int keyCode = KeyEvent.KEYCODE_VOLUME_UP;
        public String keyName = "VOLUME_UP";
        public String viewId = "";
        public String childText = null;
        public Pattern childRegex = null;

        public boolean matchesPackage(String p) {
            return pkg == null || pkg.trim().isEmpty() || p.trim().equals(pkg.trim());
        }

        public boolean matchesKey(int key) {
            return keyCode == key;
        }
    }

    public List<Profile> profiles = new ArrayList<Profile>();

    public static String template() {
        return "# 通用按键点击器配置\n"
                + "# 每个配置用 [名字] 开头，然后 3 行必填：\n"
                + "#   pkg  = 生效的应用包名\n"
                + "#   key  = 触发按键 (VOLUME_DOWN / VOLUME_UP / DPAD_UP ...)\n"
                + "#   view = 要点击的 view id (应用内 id 名字)\n"
                + "# 可选第 4 行：child = 点击 view 内文本命中的那一部分\n"
                + "[B站-音量大展开回复]\n"
                + "pkg=com.bilibili.app.in\n"
                + "key=VOLUME_UP\n"
                + "view=plugin_comment_widget\n"
                + "child=共[0-9][0-9,万wW]*条回复\n"
                + "[B站-音量减小全屏]\n"
                + "pkg=com.bilibili.app.in\n"
                + "key=VOLUME_DOWN\n"
                + "view=gemini_halfscreen_expand\n";
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Profile p : profiles) {
            sb.append('[').append(p.name).append("]\n");
            sb.append("pkg=").append(p.pkg).append("\n");
            sb.append("key=").append(p.keyName).append("\n");
            sb.append("view=").append(p.viewId).append("\n");
            if (p.childText != null && !p.childText.isEmpty()) {
                sb.append("child=").append(p.childText).append("\n");
            }
        }
        return sb.toString();
    }

    public static XConfig parse(String text) {
        XConfig cfg = new XConfig();
        if (text == null) return cfg;
        Profile cur = null;
        String[] lines = text.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if (t.startsWith("[") && t.endsWith("]")) {
                cur = new Profile();
                cur.name = t.substring(1, t.length() - 1).trim();
                cfg.profiles.add(cur);
                continue;
            }
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            String k = t.substring(0, eq).trim().toLowerCase();
            String v = t.substring(eq + 1).trim();
            if (cur == null) {
                if (k.equals("debounce_ms")) {
                    try {
                        cfg.debounceMs = Long.parseLong(v);
                    } catch (Exception e) {
                    }
                } else if (k.equals("consume_key")) {
                    cfg.consumeKey = !v.equals("0");
                }
                continue;
            }
            if (k.equals("pkg")) {
                cur.pkg = v;
            } else if (k.equals("key")) {
                cur.keyName = v;
                cur.keyCode = parseKey(v);
            } else if (k.equals("view")) {
                cur.viewId = v;
            } else if (k.equals("child")) {
                try {
                    cur.childText = v;
                    cur.childRegex = Pattern.compile(v);
                } catch (PatternSyntaxException e) {
                    cur.childRegex = null;
                }
            }
        }
        return cfg;
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