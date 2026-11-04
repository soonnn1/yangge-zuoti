package cn.tan.watchgateinstaller;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.InputStream;
import java.io.OutputStream;

/** 一个安装包内含两个签名一致的独立 App，由系统 PackageInstaller 安装。 */
public final class InstallBundleActivity extends Activity {
    private static final int ALLOW_INSTALL=100;
    private static final String[] APK_ASSETS={"watchgate.apk","yanggephoto.apk"};
    private static final String[] PACKAGE_NAMES={"cn.tan.watchgate","cn.tan.yanggephoto"};
    private TextView status;
    private Button install;

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setGravity(Gravity.CENTER_VERTICAL);
        int pad=Math.round(24*getResources().getDisplayMetrics().density);page.setPadding(pad,pad,pad,pad);page.setBackgroundColor(0xfff7f8fa);
        TextView title=new TextView(this);title.setText("洋哥做题了安装器");title.setTextSize(24);title.setTextColor(0xff1e222a);title.setGravity(Gravity.CENTER);
        TextView apps=new TextView(this);apps.setText("将分别安装两个独立 App：\n\n• 洋哥做题了\n• 洋哥拍题");apps.setTextSize(18);apps.setTextColor(0xff686f7a);apps.setPadding(0,pad,0,pad);
        status=new TextView(this);status.setText("点击安装后，按系统提示允许安装并确认。");status.setTextSize(15);status.setTextColor(0xff686f7a);status.setPadding(0,0,0,pad);
        install=new Button(this);install.setText("安装两个 App");install.setOnClickListener(v->beginInstall());
        page.addView(title,new LinearLayout.LayoutParams(-1,-2));page.addView(apps,new LinearLayout.LayoutParams(-1,-2));page.addView(status,new LinearLayout.LayoutParams(-1,-2));page.addView(install,new LinearLayout.LayoutParams(-1,-2));
        setContentView(page);showResult(getIntent());
    }

    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);showResult(intent);}

    private void showResult(Intent intent){
        if(intent==null||!intent.hasExtra("install_result"))return;
        status.setText(intent.getStringExtra("install_result"));install.setEnabled(true);
    }

    private void beginInstall(){
        if(!getPackageManager().canRequestPackageInstalls()){
            status.setText("请在系统页面允许“洋哥安装器”安装应用，返回后再点安装。");
            Intent settings=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()));
            startActivityForResult(settings,ALLOW_INSTALL);return;
        }
        install.setEnabled(false);status.setText("正在提交两个 App，请完成系统安装确认…");
        try{installBoth();}
        catch(Exception e){install.setEnabled(true);status.setText("准备安装失败，请重新打开安装器后重试。\n"+e.getMessage());}
    }

    private void installBoth() throws Exception {
        PackageInstaller installer=getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams parentParams=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        parentParams.setMultiPackage();
        int parentId=installer.createSession(parentParams);
        try(PackageInstaller.Session parent=installer.openSession(parentId)){
            for(int i=0;i<APK_ASSETS.length;i++){
                PackageInstaller.SessionParams childParams=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                childParams.setAppPackageName(PACKAGE_NAMES[i]);
                int childId=installer.createSession(childParams);
                try(PackageInstaller.Session child=installer.openSession(childId);
                    InputStream input=getAssets().open(APK_ASSETS[i]);
                    OutputStream output=child.openWrite("base.apk",0,-1)){
                    byte[] buffer=new byte[32*1024];int count;
                    while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                    child.fsync(output);
                }
                parent.addChildSessionId(childId);
            }
            Intent result=new Intent(this,InstallStatusReceiver.class).setPackage(getPackageName());
            int flags=PendingIntent.FLAG_UPDATE_CURRENT;
            if(Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
            PendingIntent callback=PendingIntent.getBroadcast(this,42,result,flags);
            parent.commit(callback.getIntentSender());
        } catch(Exception e){
            try{installer.abandonSession(parentId);}catch(Exception ignored){}
            throw e;
        }
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request==ALLOW_INSTALL&&getPackageManager().canRequestPackageInstalls())status.setText("安装授权已开启，点击“安装两个 App”继续。");
    }
}
