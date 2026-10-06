package com.qoder.sogousym;

import android.content.Context;
import android.database.Cursor;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Config storage + the two access paths:
 *  - the module app writes/reads its own SharedPreferences;
 *  - the hooked Sogou process reads the same data through {@link ConfigProvider}
 *    (cross-app prefs file access is blocked by SELinux, a provider query is not).
 */
public final class Prefs {

    /** Must match applicationId in build.gradle.kts. */
    public static final String MODULE_PKG = "com.qoder.sogousym";
    public static final String FILE = BuildConfig.EMBEDDED ? "sogousym_config" : "config";

    /** section name -> custom symbol, non-empty entries only. Called from the hook process. */
    public static Map<String, String> loadForHook(Context ctx) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        if (ctx == null) {
            return out;
        }
        try {
            Cursor c = ctx.getContentResolver().query(ConfigProvider.URI, null, null, null, null);
            if (c != null) {
                try {
                    while (c.moveToNext()) {
                        String section = c.getString(0);
                        String symbol = c.getString(1);
                        if (section != null && symbol != null && !symbol.isEmpty()) {
                            out.put(section, symbol);
                        }
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** section name -> stored symbol (may be empty/absent). */
    public static Map<String, String> load(Context c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        for (Map.Entry<String, ?> e : prefs(c).getAll().entrySet()) {
            Object v = e.getValue();
            if (v instanceof String) {
                out.put(e.getKey(), (String) v);
            }
        }
        return out;
    }

    public static void save(Context c, Map<String, String> values) {
        SharedPreferences.Editor ed = prefs(c).edit();
        ed.clear();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.isEmpty()) {
                ed.putString(e.getKey(), v);
            }
        }
        ed.commit();
    }

    public static void clear(Context c) {
        prefs(c).edit().clear().commit();
    }

    /** UI-only flag (separate file, so it never reaches the hook or gets wiped by save/clear). */
    public static boolean isHintSeen(Context c) {
        return c.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("hintSeen", false);
    }

    public static void setHintSeen(Context c) {
        c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("hintSeen", true)
                .commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private Prefs() {
    }
}
