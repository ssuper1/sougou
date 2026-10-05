package com.qoder.sogousym;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import java.util.Map;

/**
 * Exported read-only provider that hands the saved mapping to the hooked Sogou process.
 *
 * A shared prefs file cannot be used for this: Android's SELinux policy isolates each
 * app's app_data_file with per-app categories, so the Sogou process (different uid) is
 * denied reading the module's prefs even when the file is world-readable. A provider
 * query goes through ContentResolver and is allowed.
 */
public class ConfigProvider extends ContentProvider {

    public static final String AUTHORITY = "com.qoder.sogousym.config";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY + "/config");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor c = new MatrixCursor(new String[]{"section", "symbol"});
        for (Map.Entry<String, String> e : Prefs.load(getContext()).entrySet()) {
            String v = e.getValue() == null ? "" : e.getValue().trim();
            if (!v.isEmpty()) {
                c.addRow(new Object[]{e.getKey(), v});
            }
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
