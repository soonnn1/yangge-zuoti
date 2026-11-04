package cn.tan.yanggephoto;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;

/** 与主应用交换 API Key 和答题限制状态；同签名权限限制了调用方。 */
final class PhotoTutorBridge {
    private static final String MAIN_PACKAGE="cn.tan.watchgate";
    private static final Uri BRIDGE=Uri.parse("content://cn.tan.watchgate.photo_bridge");

    private PhotoTutorBridge() { }

    static boolean isMainInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(MAIN_PACKAGE,0);
            return true;
        } catch(PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    // 主应用在设备上时，同步其 Key 到本应用的加密存储，卸载主应用后仍可独立使用。
    static void syncKey(Context context) {
        if(!isMainInstalled(context))return;
        try {
            Bundle result=context.getContentResolver().call(BRIDGE,"photoTutorState",null,null);
            if(result==null||!result.containsKey("apiKey"))return;
            String key=result.getString("apiKey","").trim();
            if(key.isEmpty())ApiKeyVault.clear(context);else ApiKeyVault.save(context,key);
        } catch(Exception ignored) {
            // 主应用升级中暂时不可访问时，保留已同步的本地 Key。
        }
    }

    static boolean allowed(Context context) {
        if(!isMainInstalled(context))return true;
        try {
            Bundle result=context.getContentResolver().call(BRIDGE,"photoTutorState",null,null);
            return result!=null&&result.getBoolean("allowed",false);
        } catch(Exception ignored) {
            // 主应用存在但管控状态读不到时默认拦截，避免从桌面绕过答题锁。
            return false;
        }
    }
}
