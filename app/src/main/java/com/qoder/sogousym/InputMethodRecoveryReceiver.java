package com.qoder.sogousym;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Root can complete cleanup even if the settings activity has already closed. */
public final class InputMethodRecoveryReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.NON_ROOT && "com.qoder.sogousym.RECOVERY_CLEANUP".equals(intent.getAction())) {
            InputMethodRecovery.resetHelperIfUnused(context);
        }
    }
}
