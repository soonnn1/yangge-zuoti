package cn.tan.watchgate;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.*;
import android.widget.*;
import java.util.*;

/** 手机触屏与电视遥控器共用的家长设置页。所有规则变更均经过家长密码。 */
public final class MainActivity extends Activity {
    public static volatile boolean visible;
    private static volatile boolean parentPrompt, parentSession;
    public static boolean parentPromptVisible() { return parentPrompt; }
    private static volatile long settingsUntil;
    public static boolean parentBypass() { return visible && (parentPrompt || parentSession); }
    public static boolean settingsBypass(String pkg) {
        boolean settingsPackage=pkg.equals("com.android.settings") || pkg.equals("com.huawei.systemmanager") || pkg.equals("com.huawei.permissionmanager");
        if(!settingsPackage) return false;
        // 开启设置保护时，以家长本次验证的会话为准，退出设置即失效。
        if(WindowObserver.settingsAccessAllowed()) return true;
        return android.os.SystemClock.elapsedRealtime()<settingsUntil;
    }
    private GateState state;
    private TextView status, countdown, detail, permissions, errors, diagnostics;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean openingSystemSettings;
    private boolean returnedFromSettings, openingPermissionDialog;
    private boolean showingPermissions;
    private boolean returnToChildHome;
    private List<App> cachedApps;
    private final Runnable update = new Runnable() {
        @Override public void run() { updateStatus(); handler.postDelayed(this,1000); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); state = GateState.get(this);
        getWindow().setStatusBarColor(Ui.BG); render();
    }
    @Override protected void onResume() {
        super.onResume(); visible = true; cachedApps = null;
        if(openingSystemSettings) {
            openingSystemSettings=false;
            if(!returnedFromSettings) { leaveParentMode(); returnToChildHome=true; }
            returnedFromSettings=false;
        }
        if(returnToChildHome) { returnToChildHome=false; render(); }
        GuardService.refresh();
        if (state.hasPin()) GuardService.start(this);
        openParentIfRequested(); openBlacklistExitIfRequested(); openGestureExitIfRequested(); openProtectedSettingsIfRequested();
        if(PracticeBank.get(this).enabled()&&!parentBypass())startActivity(new Intent(this,PracticeActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        if(showingPermissions && parentSession) showPermissions();
        handler.removeCallbacks(update); handler.post(update);
    }
    @Override protected void onPause() {
        visible=false;
        handler.removeCallbacks(update); super.onPause();
    }
    @Override protected void onStop() {
        super.onStop();
        // 暂时失焦不退出家长模式；离开应用时才关闭验证状态。
        if(!openingSystemSettings && !openingPermissionDialog && !isChangingConfigurations()) {
            if(parentSession || parentPrompt) returnToChildHome=true;
            if(parentPrompt) WindowObserver.finishProtectionPrompt();
            leaveParentMode();
        }
    }
    private void leaveParentMode() { parentSession=false; parentPrompt=false; settingsUntil=0; }
    private void runInParentMode(Runnable action) {
        if(parentSession) { action.run(); return; }
        // 认证已结束时回到儿童首页，不在家长页重复弹密码。
        leaveParentMode(); render(); GuardService.refresh();
        Toast.makeText(this,"请先进入家长模式",Toast.LENGTH_SHORT).show();
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==72) returnedFromSettings=true;
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grants) {
        super.onRequestPermissionsResult(requestCode,permissions,grants);
        if(requestCode==8) openingPermissionDialog=false;
    }
    @Override public void onBackPressed() {
        if(showingPermissions && parentSession) showParentMenu();
        else if(parentSession) { leaveParentMode(); render(); GuardService.refresh(); }
        else super.onBackPressed();
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); if(visible) { openParentIfRequested(); openBlacklistExitIfRequested(); openGestureExitIfRequested(); openProtectedSettingsIfRequested(); } }
    private void openProtectedSettingsIfRequested() {
        Intent intent=getIntent();
        if(intent==null || !intent.getBooleanExtra("protect_settings_entry",false)) return;
        WindowObserver.trace("password_intent_received");
        String penaltyNotice=intent.getStringExtra("settings_penalty_notice");
        intent.removeExtra("protect_settings_entry"); intent.removeExtra("settings_penalty_notice"); leaveParentMode();
        if(state.pinWaitMs()>0) { WindowObserver.finishProtectionPrompt(); Toast.makeText(this,"密码尝试过多，请稍后再试",Toast.LENGTH_LONG).show(); leaveProtectedSettings(); return; }
        parent(() -> { WindowObserver.finishProtectionPrompt(); openSetting(Settings.ACTION_SETTINGS,null); },
            () -> { WindowObserver.finishProtectionPrompt(); leaveParentMode(); leaveProtectedSettings(); },penaltyNotice);
    }
    private void leaveProtectedSettings() {
        // 取消验证后回到桌面，不能露出后面的系统设置。
        Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(home); } catch(RuntimeException ignored) { }
        finish();
    }
    private void openParentIfRequested() {
        if(getIntent()!=null && getIntent().getBooleanExtra("parent_entry",false)) {
            getIntent().removeExtra("parent_entry"); parent(() -> {});
        }
    }
    private void openGestureExitIfRequested() {
        Intent intent=getIntent();
        if(intent==null || !intent.getBooleanExtra("gesture_exit",false)) return;
        intent.removeExtra("gesture_exit");
        leaveParentMode();
        if(state.pinWaitMs()>0) { Toast.makeText(this,"密码尝试过多，请稍后再试",Toast.LENGTH_LONG).show(); finish(); return; }
        parent(() -> {
            QuestionBank bank=QuestionBank.get(this);
            boolean blacklisted=bank.isBlacklistSession();
            String pkg=blacklisted?bank.blacklistPackage():"";
            boolean practiceMode=PracticeBank.get(this).enabled();
            try {
                // 家长验证通过后同时停止刷题，否则悬浮窗会在退出后再次出现。
                if(practiceMode) { PracticeBank.get(this).stopByParent(); GuardService.hidePracticePopup(); }
            } catch(Exception e) {
                Toast.makeText(this,e.getMessage()==null?"退出刷题失败":e.getMessage(),Toast.LENGTH_LONG).show();
                leaveParentMode(); finish(); return;
            }
            // 同时发生计时锁定和黑名单挑战时，需要分别放行。
            boolean ok=!state.locked() || state.unlock();
            if(ok && blacklisted) ok=state.allowBlacklistApp(pkg);
            if(ok) { bank.clearSession(); GuardService.refresh(); }
            else Toast.makeText(this,"放行状态保存失败，请重试",Toast.LENGTH_LONG).show();
            if(ok&&practiceMode) Toast.makeText(this,"已退出刷题模式",Toast.LENGTH_SHORT).show();
            leaveParentMode(); finish();
        },() -> { leaveParentMode(); finish(); });
    }
    private void openBlacklistExitIfRequested() {
        Intent intent=getIntent();
        if(intent==null || !intent.getBooleanExtra("blacklist_exit",false)) return;
        String pkg=intent.getStringExtra("blacklist_package"); intent.removeExtra("blacklist_exit");
        // 黑名单挑战退出按钮每次都重新验证，不能复用仍存活的家长会话。
        leaveParentMode();
        parent(() -> {
            if(!state.allowBlacklistApp(pkg)) { Toast.makeText(this,"放行状态保存失败，请重试",Toast.LENGTH_LONG).show(); return; }
            QuestionBank.get(this).clearSession(); leaveParentMode(); finish();
        },() -> { leaveParentMode(); finish(); });
    }
    private void render() {
        showingPermissions=false;
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer = Ui.column(this); outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        outer.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,12));
        LinearLayout body = Ui.column(this);
        int width = Math.min(getResources().getDisplayMetrics().widthPixels - Ui.dp(this,24),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2)); scroll.addView(outer); setContentView(scroll);
        TextView title=Ui.text(this,"洋哥做题了",22,Ui.INK); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        Ui.add(body,title,0);
        LinearLayout summary=Ui.column(this); summary.setPadding(Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12),Ui.dp(this,12));
        summary.setBackground(Ui.shape(this,Ui.CARD,0)); Ui.add(body,summary,18);
        Ui.add(summary,Ui.text(this,"剩余时间",14,Ui.MUTED),0);
        countdown=Ui.text(this,"30:00",42,Ui.INK); Ui.add(summary,countdown,4);
        status=Ui.text(this,"等待设置",14,Ui.MINT); Ui.add(summary,status,8);
        detail=Ui.text(this,"",14,Ui.MUTED); Ui.add(summary,detail,6);
        // 诊断信息由管理端展示，儿童端首页保持简洁。
        diagnostics=Ui.text(this,"",13,Ui.MUTED); permissions=Ui.text(this,"",14,Ui.MUTED); errors=Ui.text(this,"",14,Ui.MUTED);
        Ui.add(body,Ui.button(this,"家长设置",true,v -> parent(this::showParentMenu)),10);
        updateStatus();
    }
    private void showParentMenu() {
        showingPermissions=false;
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(this); outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        outer.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,12));
        LinearLayout body=Ui.column(this); int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2)); scroll.addView(outer); setContentView(scroll);
        TextView back=Ui.text(this,"‹  返回",16,Ui.MINT); back.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,10));
        back.setOnClickListener(v -> { leaveParentMode(); render(); GuardService.refresh(); }); Ui.add(body,back,0);
        TextView title=Ui.text(this,"家长设置",22,Ui.INK); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(body,title,4);
        Ui.add(body,Ui.text(this,"已进入家长模式 · 离开后自动退出",14,Ui.MUTED),6);
        LinearLayout connection=section(body,"连接");
        String address=WebAdmin.address(this);
        settingRow(connection,"连接家长手机",address.isEmpty()?"手机尚未连接 Wi-Fi":"扫码连接家长设备",null,this::showWebAccess);
        LinearLayout controls=section(body,"时间管理");
        final boolean enabled=state.enabled();
        settingRow(controls,"时间管控","",enabled,() -> {
            if(enabled) {
                if(saved(state.enable(false))) { GuardService.refresh(); Toast.makeText(this,"管控已暂停",Toast.LENGTH_SHORT).show(); }
            } else begin(false);
            if(enabled!=state.enabled()) showParentMenu();
        });
        addTimeSlider(controls);
        // 常用项目直接展示，避免“更多设置”的额外一层。
        LinearLayout device=section(body,"设备设置");
        settingRow(device,"权限和后台运行","",null,this::showPermissions);
        boolean settingsProtection=WindowObserver.settingsProtectionEnabled(this);
        settingRow(device,"设置保护",settingsProtection?"打开系统设置需要家长密码":"需先开启无障碍服务",settingsProtection,() -> {
            if(!settingsProtection && !serviceEnabled("enabled_accessibility_services",WindowObserver.class)) {
                Toast.makeText(this,"请先在权限和后台运行中开启无障碍服务",Toast.LENGTH_LONG).show(); return;
            }
            if(WindowObserver.setSettingsProtection(this,!settingsProtection)) {
                settingsUntil=0; WindowObserver.endSettingsAccess(); showParentMenu();
            }
            else Toast.makeText(this,"设置保存失败",Toast.LENGTH_LONG).show();
        });
        android.app.admin.DevicePolicyManager policy=(android.app.admin.DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName admin=new ComponentName(this,ParentDeviceAdmin.class);
        boolean deviceOwner=policy!=null && policy.isDeviceOwnerApp(getPackageName());
        if(Build.VERSION.SDK_INT>=30 && deviceOwner) {
            boolean protectedApp=policy.getUserControlDisabledPackages(admin).contains(getPackageName());
            settingRow(device,"防止强停和卸载",protectedApp?"家长可在此解除":"由设备所有者保护",protectedApp,() -> {
                try {
                    // 通过家长模式启用或解除系统保护，供家长维护设备。
                    if(protectedApp) {
                        policy.setUninstallBlocked(admin,getPackageName(),false);
                        policy.setUserControlDisabledPackages(admin,Collections.emptyList());
                    } else {
                        policy.setUserControlDisabledPackages(admin,Collections.singletonList(getPackageName()));
                        try { policy.setUninstallBlocked(admin,getPackageName(),true); }
                        catch(RuntimeException e) { policy.setUserControlDisabledPackages(admin,Collections.emptyList()); throw e; }
                    }
                    showParentMenu();
                } catch(RuntimeException e) {
                    Toast.makeText(this,"系统未允许更改设备保护："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
                }
            });
        } else {
            settingRow(device,"设备所有者保护",Build.VERSION.SDK_INT<30?"需要 Android 11 或以上":"尚未配置设备所有者",null,() ->
                new AlertDialog.Builder(this).setTitle("设备所有者保护").setMessage("需要由家长使用电脑配置设备所有者；配置完成后可在此控制防强停和卸载。")
                    .setPositiveButton("知道了",null).show());
        }
        if(deviceOwner) settingRow(device,"退出设备所有者模式","解除防强停和卸载保护",null,() ->
            new AlertDialog.Builder(this).setTitle("退出设备所有者模式")
                .setMessage("退出后防强停和卸载保护会失效，重新配置可能需要初始化设备。应用数据会保留。")
                .setNegativeButton("取消",null).setPositiveButton("确认退出",(dialog,which) -> {
                    try { OwnerRelease.release(this); Toast.makeText(this,"已退出设备所有者模式",Toast.LENGTH_LONG).show(); showParentMenu(); }
                    catch(RuntimeException e) { Toast.makeText(this,"退出失败："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show(); }
                }).show());
        settingRow(device,"修改家长密码","",null,() -> newPin(null));
        settingRow(device,"测试答题锁定","",null,this::startQuizTest);
        settingRow(device,"AI 题库",ApiKeyVault.isConfigured(this)?"百炼 API Key 已保存":"设置千问 API Key",null,this::showAiSettings);
    }

    private void showAiSettings() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(this); outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        outer.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,12));
        LinearLayout body=Ui.column(this); int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2)); scroll.addView(outer); setContentView(scroll);
        TextView back=Ui.text(this,"‹  返回",16,Ui.MINT); back.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,10));
        back.setOnClickListener(v -> showParentMenu()); Ui.add(body,back,0);
        TextView title=Ui.text(this,"AI 题库",22,Ui.INK); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(body,title,4);
        Ui.add(body,Ui.text(this,"连接千问 3.8 Flash，按家长网页所选年级生成全科题包。API Key 只在本机加密保存；每次生成按百炼账号用量计费。",14,Ui.MUTED),8);
        LinearLayout box=section(body,"模型连接");
        settingRow(box,"百炼 API Key",ApiKeyVault.isConfigured(this)?"已加密保存在本机":"尚未设置",null,this::editQwenKey);
        settingRow(box,"删除 API Key","清除本机保存的密钥",null,() -> new AlertDialog.Builder(this).setTitle("删除 API Key？")
            .setMessage("删除后，家长网页将不能再生成题库。已保存的题目不受影响。")
            .setPositiveButton("删除",(d,w) -> { ApiKeyVault.clear(this); showAiSettings(); })
            .setNegativeButton("取消",null).show());
    }

    private void editQwenKey() {
        EditText input=new EditText(this); input.setSingleLine(true); input.setHint("粘贴百炼 API Key");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setTransformationMethod(PasswordTransformationMethod.getInstance());
        LinearLayout content=Ui.column(this); content.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),0); content.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("设置千问 API Key").setView(content)
            .setPositiveButton("加密保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String key=input.getText().toString().trim();
            if(key.length()<20) { input.setError("请检查百炼 API Key"); return; }
            try { ApiKeyVault.save(this,key); dialog.dismiss(); showAiSettings(); }
            catch(Exception e) { input.setError("保存失败，请重试"); }
        })); dialog.show();
    }
    private void startQuizTest() {
        if(!hasUsageAccess(this) || !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("先开启必要权限")
                .setMessage("请先开启使用情况访问和显示在其他应用上层。")
                .setPositiveButton("知道了",null).show(); return;
        }
        if(!saved(state.startQuizTest())) return;
        leaveParentMode(); render(); GuardService.refresh();
    }
    private static String formatLimitMinutes(int minutes) {
        if(minutes<=60) return minutes+" 分钟";
        return (minutes/60)+" 小时"+(minutes%60==0?"":" "+(minutes%60)+" 分钟");
    }
    private void addTimeSlider(LinearLayout menu) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this,12),0,Ui.dp(this,12),Ui.dp(this,8));
        SeekBar slider=new SeekBar(this); slider.setMin(1); slider.setMax(120);
        slider.setProgress((int)Math.max(1,Math.min(120,(state.limit()+59_999)/60_000)));
        slider.setContentDescription("每轮使用时间，1 至 120 分钟");
        row.addView(slider,new LinearLayout.LayoutParams(0,Ui.dp(this,48),1));
        TextView value=Ui.text(this,state.demo()?"30 秒":formatLimitMinutes((int)(state.limit()/60_000)),14,Ui.INK);
        value.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams valueLayout=new LinearLayout.LayoutParams(Ui.dp(this,105),Ui.dp(this,48));
        valueLayout.leftMargin=Ui.dp(this,8); row.addView(value,valueLayout); menu.addView(row);
        final boolean[] dragging={false};
        Runnable save=() -> {
            if(!parentSession) { runInParentMode(() -> {}); return; }
            long limit=slider.getProgress()*60_000L;
            if(limit==state.limit() && !state.demo()) return;
            if(saved(state.setLimitKeepingUsage(limit))) { GuardService.refresh(); Toast.makeText(this,"时间已更新",Toast.LENGTH_SHORT).show(); }
            else { slider.setProgress((int)Math.max(1,Math.min(120,(state.limit()+59_999)/60_000))); value.setText(formatLimitMinutes((int)(state.limit()/60_000))); }
        };
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(!fromUser) return;
                value.setText(formatLimitMinutes(progress)); handler.removeCallbacks(save);
                // 遥控器方向键没有触摸结束事件，短暂停顿后保存。
                if(!dragging[0]) handler.postDelayed(save,500);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { dragging[0]=true; handler.removeCallbacks(save); }
            @Override public void onStopTrackingTouch(SeekBar bar) { dragging[0]=false; handler.removeCallbacks(save); save.run(); }
        });
    }
    private LinearLayout section(LinearLayout body,String title) {
        if(!title.isEmpty()) Ui.add(body,Ui.text(this,title,14,Ui.MUTED),14);
        LinearLayout box=Ui.column(this); box.setBackground(Ui.shape(this,Ui.CARD,0)); Ui.add(body,box,6); return box;
    }
    private void settingRow(LinearLayout box,String label,String subtitle,Boolean switchState,Runnable action) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8)); row.setMinimumHeight(Ui.dp(this,subtitle.isEmpty()?48:58));
        LinearLayout labels=Ui.column(this); row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Ui.add(labels,Ui.text(this,label,16,Ui.INK),0);
        if(!subtitle.isEmpty()) Ui.add(labels,Ui.text(this,subtitle,13,Ui.MUTED),3);
        if(switchState==null) row.addView(Ui.text(this,"›",22,Ui.MUTED));
        else {
            // 整行都是开关热区，既方便触屏也方便遥控器操作。
            Switch toggle=new Switch(this); toggle.setChecked(switchState); toggle.setClickable(false); toggle.setFocusable(false);
            toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.addView(toggle);
            row.setContentDescription(label+"，"+(switchState?"已开启":"已暂停")+"，点击切换");
        }
        android.util.TypedValue selectable=new android.util.TypedValue();
        if(getTheme().resolveAttribute(android.R.attr.selectableItemBackground,selectable,true)) row.setBackgroundResource(selectable.resourceId);
        row.setFocusable(true); row.setClickable(true);
        row.setOnClickListener(v -> runInParentMode(action)); box.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    private void showPermissions() {
        showingPermissions=true;
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(this); outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        outer.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,12));
        LinearLayout body=Ui.column(this); int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,24),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2)); scroll.addView(outer); setContentView(scroll);
        TextView back=Ui.text(this,"‹  返回",16,Ui.MINT); back.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,10));
        back.setOnClickListener(v -> showParentMenu()); Ui.add(body,back,0);
        TextView title=Ui.text(this,"权限设置",22,Ui.INK); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(body,title,4);
        Ui.add(body,Ui.text(this,"点击开关进入系统设置，返回后自动更新",14,Ui.MUTED),6);
        LinearLayout required=section(body,"必要权限");
        permissionRow(required,"使用情况访问",hasUsageAccess(this),() -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS,null));
        permissionRow(required,"显示在其他应用上层",Settings.canDrawOverlays(this),() -> openSetting(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
        LinearLayout optional=section(body,"可选权限");
        android.app.admin.DevicePolicyManager devicePolicy=(android.app.admin.DevicePolicyManager)getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName deviceAdmin=new ComponentName(this,ParentDeviceAdmin.class);
        boolean adminEnabled=devicePolicy!=null && devicePolicy.isAdminActive(deviceAdmin);
        permissionRow(optional,"设备管理应用",adminEnabled,() -> {
            if(adminEnabled) openSetting(Settings.ACTION_SECURITY_SETTINGS,null);
            else {
                Intent intent=new Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,deviceAdmin);
                intent.putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,"启用后，卸载前需要先解除设备管理授权。");
                openSystemIntent(intent);
            }
        });
        settingRow(optional,"创建桌面快捷方式","在儿童手机确认添加",null,this::requestDesktopShortcut);
        permissionRow(optional,"设置保护及答题防下拉",serviceEnabled("enabled_accessibility_services",WindowObserver.class),() -> openSetting(Settings.ACTION_ACCESSIBILITY_SETTINGS,null));
        permissionRow(optional,"暂停播放增强",serviceEnabled("enabled_notification_listeners",MediaAccess.class),() -> openSetting(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,null));
        LinearLayout background=section(body,"后台运行");
        settingRow(background,"后台运行与电池","状态需要在系统中查看",null,() -> openSetting(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
    }
    private boolean serviceEnabled(String setting,Class<?> type) {
        String value=Settings.Secure.getString(getContentResolver(),setting);
        if(value==null || value.isEmpty()) return false;
        ComponentName target=new ComponentName(this,type);
        for(String part:value.split(":")) {
            ComponentName component=ComponentName.unflattenFromString(part);
            if(target.equals(component)) return true;
        }
        return false;
    }
    private void requestDesktopShortcut() {
        try {
            android.content.pm.ShortcutManager manager=getSystemService(android.content.pm.ShortcutManager.class);
            if(manager==null || !manager.isRequestPinShortcutSupported()) {
                Toast.makeText(this,"当前桌面不支持添加快捷方式",Toast.LENGTH_LONG).show(); return;
            }
            // 快捷方式直接打开主界面，系统桌面决定是否接受隐藏状态下的请求。
            Intent intent=new Intent(this,MainActivity.class).setAction(Intent.ACTION_VIEW);
            android.content.pm.ShortcutInfo shortcut=new android.content.pm.ShortcutInfo.Builder(this,"watchgate_home")
                .setShortLabel("洋哥做题了").setLongLabel("打开洋哥做题了")
                .setIcon(android.graphics.drawable.Icon.createWithResource(this,R.drawable.icon))
                .setIntent(intent).build();
            boolean requested=manager.requestPinShortcut(shortcut,null);
            Toast.makeText(this,requested?"请在系统提示中确认添加":"系统桌面未接受添加请求",Toast.LENGTH_LONG).show();
        } catch(RuntimeException e) {
            Toast.makeText(this,"无法添加快捷方式，当前桌面或隐藏状态可能不支持",Toast.LENGTH_LONG).show();
        }
    }
    private void permissionRow(LinearLayout box,String label,boolean enabled,Runnable open) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this,12),Ui.dp(this,8),Ui.dp(this,12),Ui.dp(this,8)); row.setMinimumHeight(Ui.dp(this,56));
        LinearLayout labels=Ui.column(this); row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Ui.add(labels,Ui.text(this,label,16,Ui.INK),0); Ui.add(labels,Ui.text(this,enabled?"已开启":"未开启",13,Ui.MUTED),2);
        Switch toggle=new Switch(this); toggle.setChecked(enabled); toggle.setContentDescription(label);
        // 权限只能由系统授权；开关显示真实状态，操作时转到系统设置。
        final boolean[] restoring={false};
        toggle.setOnCheckedChangeListener((button,checked) -> {
            if(restoring[0]) return;
            restoring[0]=true; button.setChecked(enabled); restoring[0]=false;
            runInParentMode(open);
        });
        row.addView(toggle); row.setFocusable(true); row.setClickable(true);
        row.setOnClickListener(v -> toggle.performClick()); box.addView(row,new LinearLayout.LayoutParams(-1,-2));
    }
    public static boolean hasUsageAccess(Context context) {
        try {
            AppOpsManager manager = (AppOpsManager)context.getSystemService(APP_OPS_SERVICE);
            return manager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),context.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (RuntimeException e) { return false; }
    }
    private void updateStatus() {
        if (state == null || status == null) return;
        boolean usage = hasUsageAccess(this), overlay = Settings.canDrawOverlays(this);
        String text = !state.hasPin() ? "先设置家长密码" : !state.controlActive() ? "管控未开启" : (!usage || !overlay) ? "权限不足，当前无法完整管控" : !GuardService.isRunning() ? "管控服务未运行，请点击恢复" : state.locked() ? "本轮已到时 · 等待答题" : state.scheduledActive()?"定时管控中 · 亮屏时计时":"整机管控已开启 · 亮屏时计时";
        status.setText(text); countdown.setText(Ui.duration(state.limit()-state.used()));
        long age=state.heartbeat()==0 ? -1 : Math.max(0,System.currentTimeMillis()-state.heartbeat())/1000;
        diagnostics.setText("后台心跳："+(age<0?"暂无":age+" 秒前")+"\n最近一次后台状态：\n"+state.backgroundSummary());
        if(state.controlActive() && usage && overlay && age>8 && GuardService.isRunning()) status.setText("后台计时暂未响应，请在管理端查看");
        detail.setText((state.demo() ? "30 秒体验，答对后恢复正常时长" : "每轮 " + (state.limit()/60_000) + " 分钟")
            + "\n受管控：整部手机");
        permissions.setText("家长密码 " + (state.hasPin()?"✓":"未设置") + "   使用情况 " + (usage?"✓":"未授权") + "   悬浮窗 " + (overlay?"✓":"未授权"));
        errors.setText(state.error().isEmpty() ? "" : "运行提示：" + state.error());
    }
    private boolean saved(boolean okay) {
        if (!okay) Toast.makeText(this,"保存失败，请检查设备存储空间",Toast.LENGTH_LONG).show();
        updateStatus(); return okay;
    }
    private void parent(Runnable action) { parent(action,null); }
    private void parent(Runnable action,Runnable cancelled) { parent(action,cancelled,null); }
    private void parent(Runnable action,Runnable cancelled,String notice) {
        WindowObserver.trace("parent_gate pin="+state.hasPin()+" session="+parentSession+" waiting="+(state.pinWaitMs()>0));
        if (!state.hasPin()) { newPin(() -> { parentSession=true; action.run(); }); return; }
        if (parentSession) { action.run(); return; }
        if (state.pinWaitMs() > 0) { Toast.makeText(this,"密码尝试过多，请稍后再试",Toast.LENGTH_LONG).show(); return; }
        parentPrompt=true; GuardService.refresh();
        EditText input = pinField("家长密码");
        LinearLayout fields=Ui.column(this); fields.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),0);
        if(notice!=null && !notice.isEmpty()) Ui.add(fields,Ui.text(this,notice,15,Ui.INK),0);
        Ui.add(fields,input,notice==null?0:12);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("家长验证").setView(fields)
            .setPositiveButton("确认",null).setNegativeButton("取消",null).create();
        final boolean[] accepted={false};
        dialog.setOnShowListener(d -> { WindowObserver.trace("password_dialog_shown"); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (state.checkPin(input.getText().toString())) {
                accepted[0]=true; parentSession=true; dialog.dismiss(); action.run();
            } else { input.setText(""); input.setError(state.pinWaitMs()>0 ? "尝试过多，请等 30 秒" : "密码不正确"); }
        }); }); dialog.setOnDismissListener(d -> {
            WindowObserver.trace("password_dialog_dismissed accepted="+accepted[0]);
            parentPrompt=false; GuardService.refresh(); if(!accepted[0] && cancelled!=null) cancelled.run();
        }); dialog.show();
    }
    private EditText pinField(String hint) {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD); return input;
    }
    private View padded(View child) {
        LinearLayout wrap = Ui.column(this); wrap.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),0); wrap.addView(child); return wrap;
    }
    private void newPin(Runnable after) {
        EditText first = pinField("4～8 位数字"), second = pinField("再输入一次");
        LinearLayout fields = Ui.column(this); fields.setPadding(Ui.dp(this,16),Ui.dp(this,6),Ui.dp(this,16),0);
        fields.addView(first); fields.addView(second);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(state.hasPin()?"修改家长密码":"设置家长密码")
            .setMessage("请记住密码。更改规则、放行和关闭管控都需要验证。")
            .setView(fields).setPositiveButton("保存",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = first.getText().toString();
            if (!pin.matches("[0-9]{4,8}")) { first.setError("请输入 4～8 位数字"); return; }
            if (!pin.equals(second.getText().toString())) { second.setError("两次密码不一致"); return; }
            if (saved(state.setPin(pin))) { dialog.dismiss(); if (after != null) after.run(); }
        })); dialog.show();
    }
    private static final class App {
        final String pkg, label;
        App(String pkg,String label) { this.pkg=pkg; this.label=label; }
    }
    private List<App> apps() {
        if (cachedApps != null) return cachedApps;
        PackageManager manager = getPackageManager(); Map<String,App> unique = new HashMap<>();
        for (String category : new String[]{Intent.CATEGORY_LAUNCHER,Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(category);
            for (ResolveInfo info : manager.queryIntentActivities(intent,0)) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equals(getPackageName())) unique.put(pkg,new App(pkg,info.loadLabel(manager).toString()));
            }
        }
        List<App> list = new ArrayList<>(unique.values());
        list.sort((a,b) -> a.label.compareToIgnoreCase(b.label)); cachedApps = list; return list;
    }
    private void showUsage() {
        if(!hasUsageAccess(this)) {
            new AlertDialog.Builder(this).setTitle("先授予使用情况访问")
                .setMessage("授权后可统计本机各 App 今日的前台使用时长。统计保存在系统本地，不上传。")
                .setPositiveButton("去授权",(d,w) -> parent(() -> openSetting(Settings.ACTION_USAGE_ACCESS_SETTINGS,null)))
                .setNegativeButton("取消",null).show(); return;
        }
        Set<String> launchable = new HashSet<>(); for(App app:apps()) launchable.add(app.pkg);
        ProgressDialog progress = ProgressDialog.show(this,"今日使用统计","正在读取本机系统记录…",true,false);
        new Thread(() -> {
            try {
                List<UsageReport.Entry> entries = UsageReport.today(this,launchable,state.whitelist());
                runOnUiThread(() -> {
                    progress.dismiss(); if(isFinishing() || isDestroyed()) return;
                    long total=0; for(UsageReport.Entry entry:entries) total+=entry.milliseconds;
                    LinearLayout content=Ui.column(this); content.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),Ui.dp(this,20));
                    Ui.add(content,Ui.text(this,"今日合计  "+UsageReport.format(total),22,Ui.MINT),12);
                    Ui.add(content,Ui.text(this,"按前台使用时长排序，含未使用的桌面应用。\n系统记录估算；小窗、分屏和厂商行为可能造成偏差。",14,Ui.MUTED),10);
                    for(UsageReport.Entry entry:entries) {
                        Ui.add(content,Ui.text(this,entry.label+"   "+UsageReport.format(entry.milliseconds),17,Ui.INK),18);
                        Ui.add(content,Ui.text(this,entry.pkg,12,Ui.MUTED),2);
                        View line=new View(this); line.setBackgroundColor(Ui.CARD);
                        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,Ui.dp(this,1)); p.topMargin=Ui.dp(this,12); content.addView(line,p);
                    }
                    ScrollView report=new ScrollView(this); report.addView(content);
                    new AlertDialog.Builder(this).setTitle("今日各 App 使用时长").setView(report)
                        .setPositiveButton("关闭",null).setNeutralButton("刷新",(d,w) -> showUsage()).show();
                });
            } catch(RuntimeException e) {
                runOnUiThread(() -> { progress.dismiss(); if(!isFinishing() && !isDestroyed())
                    Toast.makeText(this,"无法读取，请确认使用情况访问权限后再试",Toast.LENGTH_LONG).show(); });
            }
        },"usage-report").start();
    }
    private String selectedNames() {
        List<String> names = new ArrayList<>(); Set<String> selected = state.targets();
        for (App app:apps()) if (selected.contains(app.pkg)) names.add(app.label);
        return android.text.TextUtils.join("、",names);
    }
    private void chooseApps() {
        chooseApps(null);
    }
    private void chooseApps(Runnable after) {
        List<App> all = apps();
        if (all.isEmpty()) { Toast.makeText(this,"系统未返回可启动应用列表",Toast.LENGTH_LONG).show(); return; }
        String[] labels = new String[all.size()]; boolean[] checked = new boolean[all.size()];
        Set<String> selected = state.targets();
        for (int i=0;i<all.size();i++) { labels[i]=all.get(i).label+"\n"+all.get(i).pkg; checked[i]=selected.contains(all.get(i).pkg); }
        new AlertDialog.Builder(this).setTitle("受管控应用 · 共用一轮额度")
            .setMultiChoiceItems(labels,checked,(d,index,value) -> checked[index]=value)
            .setPositiveButton("保存",(d,w) -> {
                Set<String> value = new HashSet<>();
                for (int i=0;i<all.size();i++) if(checked[i]) value.add(all.get(i).pkg);
                if(saved(state.setTargets(value))) { GuardService.refresh(); if(after!=null && !value.isEmpty()) after.run(); }
            }).setNegativeButton("取消",null).show();
    }
    private void begin(boolean demo) {
        if (!hasUsageAccess(this) || !Settings.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("还需要两项授权").setMessage("请先完成使用情况访问、显示在其他应用上层，然后回来开启。")
                .setPositiveButton("知道了",null).show(); return;
        }
        if (demo && !saved(state.startDemo())) return;
        if (!saved(state.enable(true))) return;
        state.error(""); GuardService.start(this); GuardService.refresh();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            openingPermissionDialog=true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
        }
        Toast.makeText(this,demo?"已开启 30 秒体验，亮屏时计时":"已开启整机管控，亮屏时计时",Toast.LENGTH_LONG).show();
        updateStatus();
    }
    private void launchTarget() {
        List<App> choices = new ArrayList<>();
        for (App app:apps()) if(state.targets().contains(app.pkg)) choices.add(app);
        if(choices.isEmpty()) { Toast.makeText(this,"请先由家长选择本机应用",Toast.LENGTH_LONG).show(); return; }
        String[] labels = new String[choices.size()]; for(int i=0;i<labels.length;i++) labels[i]=choices.get(i).label;
        if (choices.size()==1) launch(choices.get(0).pkg);
        else new AlertDialog.Builder(this).setTitle("打开应用").setItems(labels,(d,w) -> launch(choices.get(w).pkg)).show();
    }
    private void launch(String pkg) {
        Intent intent = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if(intent==null) intent=getPackageManager().getLaunchIntentForPackage(pkg);
        try { if(intent!=null) startActivity(intent); else Toast.makeText(this,"应用没有可用的启动入口",Toast.LENGTH_LONG).show(); }
        catch(RuntimeException e) { Toast.makeText(this,"系统未允许启动此应用",Toast.LENGTH_LONG).show(); }
    }
    private void configure() {
        String[] labels={"每轮 15 分钟","每轮 30 分钟","每轮 45 分钟","每轮 60 分钟","题目：20 以内加减","题目：100 以内加减","题目：乘除法"};
        new AlertDialog.Builder(this).setTitle("规则设置 · 修改时长将开启新一轮").setItems(labels,(d,w) -> {
            if(w<4) saved(state.setLimit(new long[]{15,30,45,60}[w]*60_000)); else saved(state.setLevel(w-3));
            GuardService.refresh();
        }).setNegativeButton("取消",null).show();
    }
    private void openSetting(String action,Uri uri) {
        Intent intent=new Intent(action); if(uri!=null) intent.setData(uri);
        openSystemIntent(intent);
    }
    private void openSystemIntent(Intent intent) {
        try {
            // 父母已验证，放行当前设置会话；无障碍服务观察到离开后立即关闭。
            if(WindowObserver.settingsProtectionEnabled(this)) {
                settingsUntil=0; WindowObserver.beginSettingsAccess();
            } else settingsUntil=SystemClock.elapsedRealtime()+90_000;
            openingSystemSettings=true; returnedFromSettings=false;
            startActivityForResult(intent,72);
        } catch(ActivityNotFoundException e) {
            try { startActivityForResult(new Intent(Settings.ACTION_SETTINGS),72); }
            catch(RuntimeException ignored) { openingSystemSettings=false; WindowObserver.endSettingsAccess(); Toast.makeText(this,"请手动进入系统设置授权",Toast.LENGTH_LONG).show(); }
        }
    }
    private void showWebAccess() {
        GuardService.start(this);
        String address=WebAdmin.address(this);
        if(address.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("连接家长手机").setMessage("请先将手机连接 Wi-Fi")
                .setPositiveButton("知道了",null).show(); return;
        }
        String url="http://"+address+":8765/";
        LinearLayout content=Ui.column(this); content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(Ui.dp(this,20),0,Ui.dp(this,20),Ui.dp(this,8));
        TextView hint=Ui.text(this,"家长手机与儿童手机连接同一个 Wi-Fi",14,Ui.MUTED);
        hint.setGravity(Gravity.CENTER); Ui.add(content,hint,0);
        addConnectionQr(content,"扫码下载家长 App",url+"parent.apk");
        addConnectionQr(content,"扫码打开家长网页",url);
        addCopyAddress(content,"家长 App 连接地址",address);
        addCopyAddress(content,"网页地址",url);
        TextView fallback=Ui.text(this,"使用家长密码连接",14,Ui.MUTED);
        fallback.setGravity(Gravity.CENTER); Ui.add(content,fallback,8);
        ScrollView scroll=new ScrollView(this); scroll.addView(content);
        new AlertDialog.Builder(this).setTitle("连接家长手机").setView(scroll).setPositiveButton("完成",null).show();
    }
    private void addCopyAddress(LinearLayout content,String label,String value) {
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        TextView text=Ui.text(this,label+"："+value,14,Ui.MUTED);
        row.addView(text,new LinearLayout.LayoutParams(0,-2,1));
        TextView copy=Ui.text(this,"⧉",24,Ui.MINT); copy.setGravity(Gravity.CENTER);
        copy.setContentDescription("复制"+label); copy.setFocusable(true); copy.setClickable(true);
        copy.setOnClickListener(v -> {
            // 只复制对应地址，便于直接粘贴到家长手机或浏览器。
            ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            if(clipboard==null) { Toast.makeText(this,"无法复制地址",Toast.LENGTH_SHORT).show(); return; }
            clipboard.setPrimaryClip(ClipData.newPlainText(label,value));
            Toast.makeText(this,"地址已复制",Toast.LENGTH_SHORT).show();
        });
        row.addView(copy,new LinearLayout.LayoutParams(Ui.dp(this,48),Ui.dp(this,48)));
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2); layout.topMargin=Ui.dp(this,8);
        content.addView(row,layout);
    }
    private void addConnectionQr(LinearLayout content,String title,String url) {
        TextView label=Ui.text(this,title,16,Ui.INK); label.setGravity(Gravity.CENTER); Ui.add(content,label,12);
        try {
            ImageView image=new ImageView(this); image.setImageBitmap(QrImage.render(url));
            if(image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable)
                ((android.graphics.drawable.BitmapDrawable)image.getDrawable()).setFilterBitmap(false);
            image.setContentDescription(title);
            int size=Math.min(Ui.dp(this,180),getResources().getDisplayMetrics().widthPixels-Ui.dp(this,100));
            LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(size,size); layout.topMargin=Ui.dp(this,4); content.addView(image,layout);
        } catch(RuntimeException e) {
            Ui.add(content,Ui.text(this,"二维码生成失败："+url,14,Ui.MUTED),4);
        }
    }
}
