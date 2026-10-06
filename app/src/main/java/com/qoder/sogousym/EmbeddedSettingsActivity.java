package com.qoder.sogousym;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;

/** Reuses the settings UI with module resources and the host's private storage. */
public final class EmbeddedSettingsActivity extends MainActivity {
    private Resources moduleResources;

    @Override
    protected void attachBaseContext(Context base) {
        moduleResources = EmbeddedRuntime.resources(base);
        super.attachBaseContext(new ContextWrapper(base) {
            @Override
            public Resources getResources() {
                return moduleResources;
            }

            @Override
            public AssetManager getAssets() {
                return moduleResources.getAssets();
            }

            @Override
            public ClassLoader getClassLoader() {
                return EmbeddedSettingsActivity.class.getClassLoader();
            }
        });
    }

    @Override
    public void setTheme(int resourceId) {
        // ActivityThread initially supplies the host's theme ID, which belongs to a different APK.
        super.setTheme(R.style.AppTheme);
    }

    @Override
    public void onConfigurationChanged(Configuration configuration) {
        moduleResources.updateConfiguration(configuration, getResources().getDisplayMetrics());
        super.onConfigurationChanged(configuration);
    }
}
