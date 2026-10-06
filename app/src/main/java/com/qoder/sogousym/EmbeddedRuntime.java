package com.qoder.sogousym;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.os.Process;

import java.util.List;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Components stay in the embedded module's loader; only their names are exposed to the host. */
public final class EmbeddedRuntime {
    public static final String SETTINGS_ACTIVITY = "com.qoder.sogousym.EmbeddedSettingsActivity";
    public static final String SETTINGS_PROCESS = "com.sohu.inputmethod.sogou:sogousym";
    private static volatile String modulePath;

    static void setModulePath(String path) {
        modulePath = path;
    }

    static void install(ClassLoader appLoader) {
        final ClassLoader moduleLoader = EmbeddedRuntime.class.getClassLoader();
        ClassLoader bridge = new ClassLoader(appLoader.getParent()) {
            @Override
            protected Class<?> findClass(String name) throws ClassNotFoundException {
                if (SETTINGS_ACTIVITY.equals(name) || ConfigProvider.class.getName().equals(name)) {
                    return moduleLoader.loadClass(name);
                }
                throw new ClassNotFoundException(name);
            }
        };
        XposedHelpers.setObjectField(appLoader, "parent", bridge);
        XposedBridge.log(MainHook.TAG + "embedded settings ready; module=" + modulePath);
    }

    static Resources resources(Context host) {
        String path = modulePath;
        if (path == null) {
            throw new IllegalStateException("Embedded module path was not delivered by LSPatch");
        }
        PackageInfo archive = host.getPackageManager().getPackageArchiveInfo(path, 0);
        if (archive == null || archive.applicationInfo == null) {
            throw new IllegalStateException("Cannot read embedded settings APK: " + path);
        }
        ApplicationInfo info = archive.applicationInfo;
        info.sourceDir = path;
        info.publicSourceDir = path;
        try {
            Resources archiveResources = host.getPackageManager().getResourcesForApplication(info);
            return new Resources(archiveResources.getAssets(), host.getResources().getDisplayMetrics(),
                    host.getResources().getConfiguration());
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            throw new IllegalStateException("Cannot load embedded settings resources", e);
        }
    }

    static boolean isKeyboardProcess(int uid, int pid, String processName, int ownUid, int ownPid) {
        return uid == ownUid && pid != ownPid && MainHook.PKG.equals(processName);
    }

    static String[] restartKeyboard(Context context) {
        if (!MainHook.PKG.equals(context.getPackageName())) {
            return new String[]{"1", "设置页未运行在搜狗内"};
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return new String[]{"1", "无法获取键盘进程"};
        }
        try {
            List<ActivityManager.RunningAppProcessInfo> processes = manager.getRunningAppProcesses();
            if (processes == null) {
                return new String[]{"1", "无法获取键盘进程"};
            }
            for (ActivityManager.RunningAppProcessInfo process : processes) {
                if (isKeyboardProcess(process.uid, process.pid, process.processName,
                        Process.myUid(), Process.myPid())) {
                    Process.killProcess(process.pid);
                }
            }
            return new String[]{"0", "已应用"};
        } catch (RuntimeException e) {
            return new String[]{"1", e.getMessage() == null ? "无法重启键盘" : e.getMessage()};
        }
    }

    private EmbeddedRuntime() {
    }
}
