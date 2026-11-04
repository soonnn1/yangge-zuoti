package cn.tan.watchgateparent;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 独立家长端：使用居中卡片连接儿童手机，保存地址后自动重连。 */
public final class MainActivity extends Activity {
    private WebView web;
    private EditText address;
    private AlertDialog connectionDialog;
    private ValueCallback<Uri[]> files;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        web=new WebView(this);
        setContentView(web);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error) {
                // 只有主页面连接失败才重新询问地址，图片等资源失败不打断管理。
                if(request.isForMainFrame() && !isFinishing() && !isDestroyed()) {
                    view.loadUrl("about:blank");
                    showConnectionDialog("连接失败，请确认两部手机连接同一个 Wi-Fi，儿童端正在运行，并检查地址。");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                if(files!=null) files.onReceiveValue(null); files=callback;
                try { startActivityForResult(params.createIntent(),20); return true; }
                catch(Exception e) { files=null; callback.onReceiveValue(null); return false; }
            }
        });
        String stored=getPreferences(MODE_PRIVATE).getString("address","");
        if(!fromIntent(getIntent())) {
            if(stored.isEmpty()) showConnectionDialog(null);
            else open(stored);
        }
    }

    private int dp(int value) { return Math.round(value*getResources().getDisplayMetrics().density); }

    private void showConnectionDialog(String error) {
        if(isFinishing() || isDestroyed() || (connectionDialog!=null && connectionDialog.isShowing())) return;
        LinearLayout content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24),dp(24),dp(24),dp(8));
        TextView title=new TextView(this);
        title.setText("连接儿童手机"); title.setTextSize(21); title.setTextColor(Color.rgb(28,36,48));
        title.setTypeface(null,android.graphics.Typeface.BOLD);
        content.addView(title);
        TextView hint=new TextView(this);
        hint.setText(error==null?"两部手机连接同一个 Wi-Fi，输入儿童端「连接家长网页」中显示的地址。":error);
        hint.setTextSize(14); hint.setTextColor(error==null?Color.rgb(100,110,124):Color.rgb(180,55,45));
        hint.setLineSpacing(dp(3),1);
        LinearLayout.LayoutParams hintLayout=new LinearLayout.LayoutParams(-1,-2);
        hintLayout.topMargin=dp(12); content.addView(hint,hintLayout);
        address=new EditText(this);
        address.setSingleLine(true); address.setTextSize(16);
        address.setHint("例如 192.168.1.8");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setMinimumHeight(dp(52));
        address.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable field=new GradientDrawable();
        field.setColor(Color.rgb(245,247,250)); field.setCornerRadius(dp(10));
        field.setStroke(dp(1),Color.rgb(219,225,233)); address.setBackground(field);
        address.setText(getPreferences(MODE_PRIVATE).getString("address",""));
        address.setSelectAllOnFocus(true);
        LinearLayout.LayoutParams addressLayout=new LinearLayout.LayoutParams(-1,-2);
        addressLayout.topMargin=dp(20); content.addView(address,addressLayout);
        connectionDialog=new AlertDialog.Builder(this).setView(content)
            .setNegativeButton("取消",(dialog,which) -> {
                if(web.getUrl()==null || "about:blank".equals(web.getUrl())) finish();
            })
            .setPositiveButton("连接",null).create();
        // 校验通过后才关闭卡片，避免地址输错时弹窗消失。
        connectionDialog.setOnShowListener(dialog -> {
            connectionDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> open(address.getText().toString()));
            GradientDrawable card=new GradientDrawable();
            card.setColor(Color.WHITE); card.setCornerRadius(dp(20));
            connectionDialog.getWindow().setBackgroundDrawable(card);
            connectionDialog.getWindow().setGravity(Gravity.CENTER);
            int width=Math.min(dp(360),getResources().getDisplayMetrics().widthPixels-dp(40));
            connectionDialog.getWindow().setLayout(width,ViewGroup.LayoutParams.WRAP_CONTENT);
        });
        connectionDialog.setOnCancelListener(dialog -> {
            if(web.getUrl()==null || "about:blank".equals(web.getUrl())) finish();
        });
        address.setOnEditorActionListener((view,action,event) -> {
            if(action!=EditorInfo.IME_ACTION_GO) return false;
            open(address.getText().toString()); return true;
        });
        connectionDialog.show();
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); fromIntent(intent); }

    private boolean fromIntent(Intent intent) {
        Uri uri=intent.getData(); if(uri==null) return false;
        String host=uri.getQueryParameter("host"); if(host==null) return false;
        open(host); return true;
    }

    private void open(String host) {
        host=host.trim().replaceFirst("^https?://","").replaceAll("/.*$","");
        // 保留局域网 IPv4 地址限制，扫码不能跳转到其他网站。
        if(!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}(:8765)?")) {
            showConnectionDialog(null);
            address.setError("请输入儿童手机的局域网地址"); return;
        }
        if(host.endsWith(":8765")) host=host.substring(0,host.length()-5);
        getPreferences(MODE_PRIVATE).edit().putString("address",host).apply();
        if(connectionDialog!=null) connectionDialog.dismiss();
        web.loadUrl("http://"+host+":8765/");
    }

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==20 && files!=null) { files.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result,data)); files=null; }
    }

    @Override protected void onDestroy() {
        if(connectionDialog!=null) connectionDialog.dismiss();
        super.onDestroy();
    }

    @Override public void onBackPressed() { if(web.canGoBack()) web.goBack(); else super.onBackPressed(); }
}
