package cn.tan.watchgate;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import java.util.ArrayList;

/** 先撤销本应用的限制，再主动退出设备所有者。 */
final class OwnerRelease {
    static synchronized void release(Context context) {
        DevicePolicyManager policy=(DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if(policy==null || !policy.isDeviceOwnerApp(context.getPackageName()))
            throw new IllegalStateException("应用不是设备所有者");
        ComponentName admin=new ComponentName(context,ParentDeviceAdmin.class);
        if(Build.VERSION.SDK_INT>=30) {
            ArrayList<String> protectedApps=new ArrayList<>(policy.getUserControlDisabledPackages(admin));
            protectedApps.remove(context.getPackageName());
            policy.setUserControlDisabledPackages(admin,protectedApps);
        }
        policy.setUninstallBlocked(admin,context.getPackageName(),false);
        policy.clearDeviceOwnerApp(context.getPackageName());
        if(policy.isDeviceOwnerApp(context.getPackageName()))
            throw new IllegalStateException("系统未解除设备所有者");
    }
}
