package com.example.xclick;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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

public class MainActivity extends Activity {

    private static final String CONFIG_PREFS = "xclick_config";
    private static final String CONFIG_KEY = "config";
    private EditText editText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView info = new TextView(this);
        info.setText("配置会同时保存到:\n1. SharedPreferences (LSPosed 新XSharedPreferences)\n"
                + "2. /data/user/0/com.example.xclick/files/xclick.conf\n"
                + "3. /storage/emulated/0/ClickTrigger/config.properties (尽量)\n"
                + "\n保存后无需重启手机，重启目标应用即可生效。");
        info.setTextSize(13);
        root.addView(info);

        editText = new EditText(this);
        editText.setId(android.R.id.content + 1);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setTextSize(12);
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setMinLines(18);
        root.addView(editText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button btnSave = new Button(this);
        btnSave.setText("保存配置");
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
            }
        });

        Button btnReset = new Button(this);
        btnReset.setText("重置为模板");
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editText.setText(XConfig.template());
            }
        });

        row.addView(btnSave, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(btnReset, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        loadConfigIntoEditor();
    }

    private void loadConfigIntoEditor() {
        String text = XConfig.readFile(new File(getFilesDir(), "xclick.conf"));
        if (text == null) text = XConfig.readFile(new File(
                Environment.getExternalStorageDirectory(), "ClickTrigger/config.properties"));
        if (text == null) {
            SharedPreferences p = getSharedPreferences(CONFIG_PREFS, Context.MODE_PRIVATE);
            text = p.getString(CONFIG_KEY, "");
        }
        if (text == null || text.trim().isEmpty()) text = XConfig.template();
        editText.setText(text);
    }

    private void saveConfig() {
        String text = editText.getText().toString();
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
                Toast.makeText(this, "prefs保存失败: " + t2, Toast.LENGTH_LONG).show();
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
            Toast.makeText(this, "内部文件保存失败: " + t, Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "sdcard保存失败: " + t, Toast.LENGTH_LONG).show();
            }
        } else {
            Toast.makeText(this, "Android 10+ 跳过sdcard写入(前两个配置源已够用)", Toast.LENGTH_LONG).show();
        }

        Toast.makeText(this, "已保存 " + saved + " 个配置源，重启目标应用生效", Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
