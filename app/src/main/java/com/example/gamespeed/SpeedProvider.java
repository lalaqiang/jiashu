package com.example.gamespeed;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/**
 * 跨进程倍率同步 Provider（兼容 Android 11+）
 *
 * 用途：替代 Android 11+ 已被废弃的 MODE_WORLD_READABLE SharedPreferences。
 * 主进程写入倍率 → Hook 进程（沙箱内）通过 query() 读取最新值。
 *
 * URI: content://com.example.gamespeed.speed/speed
 * 列: speed（FLOAT 的字符串形式）
 *
 * 兼容性：双轨制——主进程同时写 SharedPreferences（老沙箱仍走 XSharedPreferences）
 *        和 ContentProvider（新系统/沙箱走此通道），Hook 端任一可用即可。
 */
public class SpeedProvider extends ContentProvider {

    public static final String AUTHORITY = "com.example.gamespeed.speed";
    public static final String PATH_SPEED = "speed";
    public static final String PREF_NAME = "speed";
    public static final String KEY_SPEED = "speed";

    public static final Uri SPEED_URI = Uri.parse("content://" + AUTHORITY + "/" + PATH_SPEED);

    private SharedPreferences sp;

    @Override
    public boolean onCreate() {
        sp = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        float speed = sp.getFloat(KEY_SPEED, 1.0f);
        MatrixCursor c = new MatrixCursor(new String[]{KEY_SPEED});
        c.addRow(new Object[]{Float.toString(speed)});
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.item/vnd.com.example.gamespeed.speed";
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
        if (values != null && values.containsKey(KEY_SPEED)) {
            float s = values.getAsFloat(KEY_SPEED);
            if (s < 0.1f) s = 0.1f;
            if (s > 20.0f) s = 20.0f;
            sp.edit().putFloat(KEY_SPEED, s).apply();
            getContext().getContentResolver().notifyChange(SPEED_URI, null);
            return 1;
        }
        return 0;
    }

    /** 主进程写入倍率（同时更新 SharedPreferences 和 Provider） */
    public static void setSpeed(Context ctx, float speed) {
        if (speed < 0.1f) speed = 0.1f;
        if (speed > 20.0f) speed = 20.0f;
        // 先写本地 SP（双轨）
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putFloat(KEY_SPEED, speed).apply();
        // 再走 Provider 通知其他进程
        try {
            ContentValues cv = new ContentValues();
            cv.put(KEY_SPEED, speed);
            ctx.getContentResolver().update(SPEED_URI, cv, null, null);
        } catch (Throwable ignored) { }
    }
}
