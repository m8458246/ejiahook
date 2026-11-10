package com.cycy.ejiahook;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

import java.util.Map;

/**
 * 只读配置通道。
 *
 * <p>被注入的 ejia 进程调用 {@code ContentResolver.call(uri, "get", null, null)} 拿到
 * 模块里保存的配置。没有权限要求，但只提供读取，不提供任何写入。</p>
 */
public class ConfigProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (!Config.METHOD_GET.equals(method)) {
            return null;
        }
        Context ctx = getContext();
        if (ctx == null) {
            return null;
        }
        Bundle out = new Bundle();
        try {
            SharedPreferences sp = ctx.getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
            for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof String) {
                    out.putString(key, (String) value);
                } else if (value instanceof Boolean) {
                    out.putBoolean(key, (Boolean) value);
                } else if (value instanceof Integer) {
                    out.putInt(key, (Integer) value);
                } else if (value instanceof Long) {
                    out.putLong(key, (Long) value);
                } else if (value instanceof Float) {
                    out.putFloat(key, (Float) value);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
