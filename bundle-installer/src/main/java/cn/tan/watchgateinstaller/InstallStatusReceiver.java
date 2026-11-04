package cn.tan.watchgateinstaller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** 接收系统安装进度；需要用户确认时只启动系统提供的确认界面。 */
public final class InstallStatusReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        int status=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
        if(status==PackageInstaller.STATUS_PENDING_USER_ACTION){
            Intent confirmation=intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if(confirmation!=null){confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(confirmation);}
            return;
        }
        String message=status==PackageInstaller.STATUS_SUCCESS?"安装完成。两个 App 已分别加入应用列表；“洋哥安装器”现在可以卸载。":"安装未完成，请按系统提示检查原因后重试。";
        Intent screen=new Intent(context,InstallBundleActivity.class).putExtra("install_result",message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        context.startActivity(screen);
    }
}
