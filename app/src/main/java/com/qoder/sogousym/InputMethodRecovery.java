package com.qoder.sogousym;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.util.concurrent.atomic.AtomicBoolean;

/** One-shot default-IME refresh; the root shell restores its temporary component on exit. */
final class InputMethodRecovery {
    static final String HELPER = "com.qoder.sogousym/android.inputmethodservice.InputMethodService";
    private static final AtomicBoolean RUNNING = new AtomicBoolean();

    static boolean begin() {
        return RUNNING.compareAndSet(false, true);
    }

    static void finish() {
        RUNNING.set(false);
    }

    static boolean isRunning() {
        return RUNNING.get();
    }

    static void enableHelper(Context context) {
        context.getPackageManager().setComponentEnabledSetting(
                ComponentName.unflattenFromString(HELPER),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
    }

    static void resetHelperIfUnused(Context context) {
        if (!HELPER.equals(Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD))) {
            context.getPackageManager().setComponentEnabledSetting(
                    ComponentName.unflattenFromString(HELPER),
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP);
        }
    }

    static String script() {
        return "helper='com.qoder.sogousym/android.inputmethodservice.InputMethodService'\n"
                + "original=$(settings get secure default_input_method)\n"
                + "watchdog=''\n"
                + "cleanup() {\n"
                + "  result=$?\n"
                + "  trap - EXIT HUP INT TERM\n"
                + "  current=$(settings get secure default_input_method)\n"
                + "  if [ \"$current\" = \"$helper\" ]; then\n"
                + "    ime set \"$original\" >/dev/null 2>&1\n"
                + "    current=$(settings get secure default_input_method)\n"
                + "    if [ \"$current\" = \"$helper\" ]; then\n"
                + "      echo '恢复搜狗失败，请在系统输入法设置中选择搜狗'\n"
                + "      result=1\n"
                + "    fi\n"
                + "  fi\n"
                + "  if [ \"$current\" != \"$helper\" ]; then\n"
                + "    ime disable \"$helper\" >/dev/null 2>&1 || result=1\n"
                + "    am broadcast --include-stopped-packages -n com.qoder.sogousym/.InputMethodRecoveryReceiver"
                + " -a com.qoder.sogousym.RECOVERY_CLEANUP >/dev/null 2>&1 || result=1\n"
                + "  fi\n"
                + "  [ -z \"$watchdog\" ] || kill \"$watchdog\" >/dev/null 2>&1\n"
                + "  [ \"$result\" = 0 ] || echo '输入法状态刷新未完成'\n"
                + "  exit \"$result\"\n"
                + "}\n"
                + "trap cleanup EXIT\n"
                + "trap 'exit 1' HUP INT TERM\n"
                + "case \"$original\" in\n"
                + "  com.sohu.inputmethod.sogou/*) ;;\n"
                + "  *) echo '请先将搜狗设为当前输入法'; exit 1 ;;\n"
                + "esac\n"
                // The shell keeps restoring even if the activity closes. Bound the whole operation.
                + "(sleep 20; kill -TERM \"$$\") </dev/null >/dev/null 2>&1 &\n"
                + "watchdog=$!\n"
                // Package broadcasts update IMMS asynchronously after DONT_KILL_APP enables it.
                + "tries=0\n"
                + "while ! ime list -a -s | grep -Fxq \"$helper\"; do\n"
                + "  tries=$((tries + 1)); [ \"$tries\" -le 5 ] || exit 1; sleep 1\n"
                + "done\n"
                + "ime enable \"$helper\" >/dev/null 2>&1 || exit 1\n"
                + "ime set \"$helper\" >/dev/null 2>&1 || exit 1\n"
                + "[ \"$(settings get secure default_input_method)\" = \"$helper\" ] || exit 1\n"
                + "sleep 2\n"
                // Preserve a different IME chosen by the user while this operation is running.
                + "[ \"$(settings get secure default_input_method)\" = \"$helper\" ] || exit 1\n"
                + "ime set \"$original\" >/dev/null 2>&1 || exit 1\n"
                + "[ \"$(settings get secure default_input_method)\" = \"$original\" ] || exit 1\n";
    }

    private InputMethodRecovery() {
    }
}
