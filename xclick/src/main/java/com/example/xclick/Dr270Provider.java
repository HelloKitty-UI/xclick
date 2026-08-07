package com.example.xclick;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.io.File;

public class Dr270Provider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        int enabled = 0;
        try {
            XConfig cfg = XConfig.parse(readConfigText());
            enabled = cfg.rotate270 ? 1 : 0;
        } catch (Throwable t) {
        }
        MatrixCursor c = new MatrixCursor(new String[]{"enabled"});
        c.addRow(new Object[]{enabled});
        return c;
    }

    private String readConfigText() {
        Context ctx = getContext();
        if (ctx == null) return null;
        String text = XConfig.readFile(new File(ctx.getFilesDir(), "xclick.conf"));
        if (text == null || text.trim().isEmpty()) {
            try {
                SharedPreferences p = ctx.getSharedPreferences("xclick_config", Context.MODE_PRIVATE);
                text = p.getString("config", "");
            } catch (Throwable t) {
            }
        }
        return text;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.example.xclick.dr270";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
