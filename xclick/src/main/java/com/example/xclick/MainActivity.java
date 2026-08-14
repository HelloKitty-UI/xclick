package com.example.xclick;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final String CONFIG_PREFS = "xclick_config";
    private static final String CONFIG_KEY = "config";

    private XConfig cfg = new XConfig();
    private LinearLayout listContainer;
    private LinearLayout editContainer;
    private LinearLayout root;
    private int editIndex = -1;
    private EditText debounceEt;
    private Button consumeBtn;
    private Button rotate270Btn;
    private Button btRotateAutoBtn;
    private TextView btStateTv;

    private EditText eName;
    private EditText ePkg;
    private EditText eKey;
    private EditText eView;
    private EditText eChild;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        loadData();
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        editContainer = new LinearLayout(this);
        editContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(listContainer);
        root.addView(editContainer);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
        editContainer.setVisibility(View.GONE);
        showList();
    }

    private void loadData() {
        String text = XConfig.readFile(new File(getFilesDir(), "xclick.conf"));
        if (text == null) text = XConfig.readFile(new File(
                Environment.getExternalStorageDirectory(), "ClickTrigger/config.properties"));
        if (text == null) {
            SharedPreferences p = getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
            text = p.getString(CONFIG_KEY, "");
        }
        if (text == null || text.trim().isEmpty()) text = XConfig.template();
        XConfig parsed = XConfig.parse(text);
        cfg.debounceMs = parsed.debounceMs;
        cfg.consumeKey = parsed.consumeKey;
        cfg.rotate270 = parsed.rotate270;
        cfg.btRotateAuto = parsed.btRotateAuto;
        cfg.profiles = parsed.profiles;
    }

    private void showList() {
        editContainer.removeAllViews();
        editContainer.setVisibility(View.GONE);
        listContainer.removeAllViews();
        listContainer.setVisibility(View.VISIBLE);

        TextView title = new TextView(this);
        title.setText("通用按键点击器 — 配置列表");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        listContainer.addView(title);

        LinearLayout globalRow = new LinearLayout(this);
        globalRow.setOrientation(LinearLayout.HORIZONTAL);
        globalRow.setGravity(Gravity.CENTER_VERTICAL);
        globalRow.setPadding(0, dp(4), 0, dp(4));

        TextView debLabel = new TextView(this);
        debLabel.setText("防抖ms:");
        globalRow.addView(debLabel);
        debounceEt = new EditText(this);
        debounceEt.setText(String.valueOf(cfg.debounceMs));
        debounceEt.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        debounceEt.setWidth(dp(80));
        debounceEt.setTextSize(14);
        globalRow.addView(debounceEt);

        TextView space = new TextView(this);
        space.setText("    ");
        globalRow.addView(space);

        consumeBtn = new Button(this);
        consumeBtn.setText(cfg.consumeKey ? "吞掉按键:开" : "吞掉按键:关");
        consumeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.consumeKey = !cfg.consumeKey;
                consumeBtn.setText(cfg.consumeKey ? "吞掉按键:开" : "吞掉按键:关");
            }
        });
        globalRow.addView(consumeBtn);

        TextView space2 = new TextView(this);
        space2.setText("    ");
        globalRow.addView(space2);

        rotate270Btn = new Button(this);
        updateRotate270Text();
        rotate270Btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.rotate270 = !cfg.rotate270;
                updateRotate270Text();
                saveConfig();
            }
        });
        globalRow.addView(rotate270Btn);

        TextView space3 = new TextView(this);
        space3.setText("    ");
        globalRow.addView(space3);

        btRotateAutoBtn = new Button(this);
        updateBtAutoText();
        btRotateAutoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cfg.btRotateAuto = !cfg.btRotateAuto;
                updateBtAutoText();
                saveConfig();
            }
        });
        globalRow.addView(btRotateAutoBtn);

        btStateTv = new TextView(this);
        btStateTv.setText(btInputStateText());
        btStateTv.setTextSize(12);
        btStateTv.setTextColor(Color.GRAY);
        btStateTv.setPadding(0, dp(4), 0, dp(4));
        listContainer.addView(btStateTv);

        Button saveGlobal = new Button(this);
        saveGlobal.setText("保存全部");
        saveGlobal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });
        globalRow.addView(saveGlobal);
        listContainer.addView(globalRow);

        TextView tip = new TextView(this);
        tip.setText("配置列表（点击“编辑”可修改）：");
        tip.setTextSize(14);
        tip.setPadding(0, dp(10), 0, dp(4));
        listContainer.addView(tip);

        if (cfg.profiles.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有配置，点下方“＋ 新建配置”添加");
            empty.setTextSize(14);
            empty.setTextColor(Color.GRAY);
            empty.setPadding(0, dp(8), 0, dp(8));
            listContainer.addView(empty);
        }

        for (int i = 0; i < cfg.profiles.size(); i++) {
            final XConfig.Profile p = cfg.profiles.get(i);
            final int idx = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));

            TextView info = new TextView(this);
            info.setText("[" + p.name + "] " + p.keyName + " -> view=" + p.viewId
                    + (p.childText != null ? " (文本:" + p.childText + ")" : "")
                    + "  包名:" + p.pkg);
            info.setTextSize(13);
            info.setPadding(0, 0, dp(6), 0);
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            Button editBtn = new Button(this);
            editBtn.setText("编辑");
            editBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEditor(idx);
                }
            });
            row.addView(editBtn);

            Button delBtn = new Button(this);
            delBtn.setText("删除");
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cfg.profiles.remove(idx);
                    saveConfig();
                    showList();
                }
            });
            row.addView(delBtn);
            listContainer.addView(row);
        }

        Button addBtn = new Button(this);
        addBtn.setText("＋ 新建配置");
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(-1);
            }
        });
        listContainer.addView(addBtn);
    }

    private void openEditor(int index) {
        editIndex = index;
        listContainer.setVisibility(View.GONE);
        editContainer.setVisibility(View.VISIBLE);
        editContainer.removeAllViews();

        XConfig.Profile p = (index >= 0 && index < cfg.profiles.size())
                ? cfg.profiles.get(index) : null;

        TextView title = new TextView(this);
        title.setText(p == null ? "新建配置" : "编辑配置");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(0, 0, 0, dp(8));
        editContainer.addView(title);

        eName = new EditText(this);
        eName.setHint("名称（如：视频全屏）");
        eName.setText(p != null ? p.name : "");
        eName.setSingleLine(true);
        editContainer.addView(eName);

        ePkg = new EditText(this);
        ePkg.setHint("生效应用包名（如 com.bilibili.app.in）");
        ePkg.setText(p != null ? p.pkg : "");
        ePkg.setSingleLine(true);
        editContainer.addView(ePkg);

        eKey = new EditText(this);
        eKey.setHint("触发按键：数字键码或名字（如 25 / 24 / VOLUME_DOWN / VOLUME_UP）");
        eKey.setText(p != null ? p.keyName : "VOLUME_DOWN");
        eKey.setSingleLine(true);
        editContainer.addView(eKey);

        eView = new EditText(this);
        eView.setHint("要点击的 view id（如 gemini_halfscreen_expand）");
        eView.setText(p != null ? p.viewId : "");
        eView.setSingleLine(true);
        editContainer.addView(eView);

        eChild = new EditText(this);
        eChild.setHint("可选：view 内要点击的子文本（如 共.*条回复），留空点整个 view");
        eChild.setText(p != null && p.childText != null ? p.childText : "");
        eChild.setSingleLine(true);
        editContainer.addView(eChild);

        TextView hint = new TextView(this);
        hint.setText("隐藏的按钮也能点中（如收起控制条时的全屏按钮），无需额外设置。");
        hint.setTextSize(12);
        hint.setTextColor(Color.GRAY);
        hint.setPadding(0, dp(4), 0, dp(4));
        editContainer.addView(hint);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(8), 0, 0);

        Button saveBtn = new Button(this);
        saveBtn.setText("保存");
        saveBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                XConfig.Profile n = new XConfig.Profile();
                n.name = eName.getText().toString().trim();
                n.pkg = ePkg.getText().toString().trim();
                n.keyName = eKey.getText().toString().trim();
                n.keyCode = XConfig.parseKey(n.keyName);
                n.viewId = eView.getText().toString().trim();
                String ch = eChild.getText().toString().trim();
                if (!ch.isEmpty()) {
                    n.childText = ch;
                    try {
                        n.childRegex = java.util.regex.Pattern.compile(ch);
                    } catch (Exception ex) {
                        n.childRegex = null;
                    }
                }
                if (n.name.isEmpty()) n.name = "配置";
                if (editIndex >= 0 && editIndex < cfg.profiles.size()) {
                    cfg.profiles.set(editIndex, n);
                } else {
                    cfg.profiles.add(n);
                }
                saveConfig();
                showList();
            }
        });
        row.addView(saveBtn);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("取消");
        cancelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showList();
            }
        });
        row.addView(cancelBtn);
        editContainer.addView(row);
    }

    private void updateRotate270Text() {
        rotate270Btn.setText(cfg.rotate270 ? "横屏固定270:开" : "横屏固定270:关");
    }

    private void updateBtAutoText() {
        btRotateAutoBtn.setText(cfg.btRotateAuto ? "蓝牙自动横屏:开" : "蓝牙自动横屏:关");
    }

    private String btInputStateText() {
        try {
            android.bluetooth.BluetoothAdapter a =
                    android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null) return "蓝牙:不可用";
            boolean has = a.getProfileConnectionState(android.bluetooth.BluetoothProfile.HID_HOST)
                    == android.bluetooth.BluetoothProfile.STATE_CONNECTED;
            return has ? "蓝牙输入设备(鼠标/手柄/键盘):已连接" : "蓝牙输入设备(鼠标/手柄/键盘):未连接";
        } catch (Throwable t) {
            return "蓝牙:不可用";
        }
    }

    private void saveConfig() {
        try {
            cfg.debounceMs = Long.parseLong(debounceEt.getText().toString());
        } catch (Exception e) {
        }
        StringBuilder sb = new StringBuilder();
        sb.append("debounce_ms=").append(cfg.debounceMs).append("\n");
        sb.append("consume_key=").append(cfg.consumeKey ? 1 : 0).append("\n");
        sb.append("rotate_270=").append(cfg.rotate270 ? 1 : 0).append("\n");
        sb.append("bt_rotate_auto=").append(cfg.btRotateAuto ? 1 : 0).append("\n");
        sb.append(cfg.toString());
        String text = sb.toString();

        int saved = 0;
        try {
            SharedPreferences p = getSharedPreferences(CONFIG_PREFS, Context.MODE_WORLD_READABLE);
            p.edit().putString(CONFIG_KEY, text).commit();
            File prefsFile = new File(getDataDir(), "shared_prefs/" + CONFIG_PREFS + ".xml");
            prefsFile.setReadable(true, false);
            saved++;
        } catch (Throwable t) {
            try {
                SharedPreferences p = getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
                p.edit().putString(CONFIG_KEY, text).commit();
                File prefsFile = new File(getDataDir(), "shared_prefs/" + CONFIG_PREFS + ".xml");
                prefsFile.setReadable(true, false);
                saved++;
            } catch (Throwable t2) {
            }
        }
        try {
            File f = new File(getFilesDir(), "xclick.conf");
            FileOutputStream out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
            saved++;
        } catch (Throwable t) {
        }
        if (Build.VERSION.SDK_INT <= 29) {
            try {
                File dir = new File(Environment.getExternalStorageDirectory(), "ClickTrigger");
                dir.mkdirs();
                File f = new File(dir, "config.properties");
                FileOutputStream out = new FileOutputStream(f);
                out.write(text.getBytes("UTF-8"));
                out.close();
                saved++;
            } catch (Throwable t) {
            }
        }
        android.util.Log.i("DR270", "saved rotate_270=" + cfg.rotate270
                + " textLen=" + text.length());
        Toast.makeText(this, "已保存。横屏270开关实时生效，其余配置重启目标应用生效", Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}