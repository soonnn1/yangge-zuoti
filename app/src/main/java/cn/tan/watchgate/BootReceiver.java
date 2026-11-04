package cn.tan.watchgate;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
            || Intent.ACTION_USER_UNLOCKED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            // 重启只恢复服务，绝不重置计时或锁定记录。
            if (GateState.get(context).hasPin()) GuardService.start(context);
        }
    }
}
