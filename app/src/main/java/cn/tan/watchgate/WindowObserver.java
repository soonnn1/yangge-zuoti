package cn.tan.watchgate;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** 可选增强：识别应用切换，并在答题锁定时尝试收起系统下拉栏。 */
public final class WindowObserver extends AccessibilityService {
    private static volatile WindowObserver active;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastShadeDismissMs, lastSettingsPenaltyMs;
    private boolean settingsWindowSeen;
    private WindowManager windowManager;
    private volatile View settingsPromptOverlay;
    private EditText settingsPromptInput;
    private TextView settingsPromptError;
    private TextView settingsPromptConfirm;
    private static volatile boolean settingsAuthorized, settingsSeen, protectionPromptPending;
    private static volatile long ignoreSelfSettingsExitUntil;
    // 仅记录事件类型及状态，不记录密码、题目或窗口文字；进程退出即清空。
    private static final java.util.ArrayDeque<String> diagnosticEvents=new java.util.ArrayDeque<>();
    static synchronized void trace(String stage) {
        if(diagnosticEvents.size()>=40) diagnosticEvents.removeFirst();
        diagnosticEvents.addLast(SystemClock.elapsedRealtime()+" "+stage);
    }
    @Override protected void dump(java.io.FileDescriptor fd,java.io.PrintWriter out,String[] args) {
        out.println("settingsProtection="+settingsProtectionEnabled(this)+" pinPresent="+GateState.get(this).hasPin()
            +" authorized="+settingsAuthorized+" settingsSeen="+settingsSeen+" promptPending="+protectionPromptPending
            +" overlayVisible="+(active!=null && active.settingsPromptOverlay!=null)
            +" bypass="+MainActivity.settingsBypass("com.android.settings")+" activityVisible="+MainActivity.visible);
        synchronized(WindowObserver.class) { for(String entry:diagnosticEvents) out.println(entry); }
    }
    /** 已验证的设置访问仅在当前设置会话内有效。 */
    public static void beginSettingsAccess() { settingsAuthorized=true; settingsSeen=false; }
    public static void endSettingsAccess() { settingsAuthorized=false; settingsSeen=false; }
    public static boolean settingsAccessAllowed() { return settingsAuthorized; }
    public static void finishProtectionPrompt() {
        protectionPromptPending=false;
        WindowObserver service=active;
        if(service!=null) service.mainHandler.post(service::removeSettingsPrompt);
    }
    private static void approveObservedSettingsAccess() {
        settingsAuthorized=true;
        settingsSeen=true;
        // 忽略遮罩移除后紧随其来的本应用窗口事件，避免刚验证就立刻失效。
        ignoreSelfSettingsExitUntil=SystemClock.elapsedRealtime()+900;
    }
    private static boolean systemSettingsPackage(String pkg) {
        return pkg.equals("com.android.settings") || pkg.equals("com.huawei.systemmanager") || pkg.equals("com.huawei.permissionmanager");
    }
    private void noteSettingsWindow(String pkg) {
        if(!settingsAuthorized) return;
        if(systemSettingsPackage(pkg)) { settingsSeen=true; return; }
        if(pkg.equals(getPackageName()) && SystemClock.elapsedRealtime()<ignoreSelfSettingsExitUntil) {
            ignoreSelfSettingsExitUntil=0;
            trace("settings_self_overlay_event_ignored");
            return;
        }
        // 系统弹窗和输入法不是离开设置，不应中断家长操作。
        String ime=android.provider.Settings.Secure.getString(getContentResolver(),android.provider.Settings.Secure.DEFAULT_INPUT_METHOD);
        if(pkg.equals("android") || pkg.equals("com.android.systemui") || (ime!=null && ime.startsWith(pkg+"/"))) return;
        if(settingsSeen) endSettingsAccess();
    }
    private int promptDp(float value) { return (int)(value*getResources().getDisplayMetrics().density+0.5f); }
    private GradientDrawable promptShape(int color,float radius) {
        GradientDrawable shape=new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(promptDp(radius)); return shape;
    }
    private TextView promptText(String text,int size,int color,boolean bold) {
        TextView view=new TextView(this); view.setText(text); view.setTextSize(size); view.setTextColor(color);
        if(bold) view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return view;
    }
    private void showSettingsPrompt(String notice) {
        if(settingsPromptOverlay!=null) return;
        GateState state=GateState.get(this);
        if(!settingsProtectionEnabled(this) || !state.hasPin() || MainActivity.settingsBypass("com.android.settings")) {
            protectionPromptPending=false; return;
        }
        try {
            if(windowManager==null) windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
            if(windowManager==null) throw new IllegalStateException("window manager unavailable");
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            int pad=promptDp(22); card.setPadding(pad,pad,pad,promptDp(18));
            card.setBackground(promptShape(Color.WHITE,18)); card.setElevation(promptDp(12));
            TextView title=promptText("家长验证",22,Color.rgb(31,43,56),true);
            card.addView(title,new LinearLayout.LayoutParams(-1,-2));
            TextView hint=promptText("进入系统设置前，请输入家长密码。",15,Color.rgb(94,108,123),false);
            LinearLayout.LayoutParams hintLp=new LinearLayout.LayoutParams(-1,-2); hintLp.topMargin=promptDp(8); card.addView(hint,hintLp);
            if(notice!=null && !notice.isEmpty()) {
                TextView note=promptText(notice,14,Color.rgb(94,108,123),false);
                LinearLayout.LayoutParams noteLp=new LinearLayout.LayoutParams(-1,-2); noteLp.topMargin=promptDp(10); card.addView(note,noteLp);
            }
            EditText input=new EditText(this); settingsPromptInput=input;
            input.setSingleLine(true); input.setHint("输入 4～8 位家长密码");
            input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            input.setPadding(promptDp(12),0,promptDp(12),0); input.setTextSize(17);
            GradientDrawable inputBorder=promptShape(Color.WHITE,10); inputBorder.setStroke(promptDp(1),Color.rgb(190,202,214)); input.setBackground(inputBorder);
            LinearLayout.LayoutParams inputLp=new LinearLayout.LayoutParams(-1,promptDp(52)); inputLp.topMargin=promptDp(16); card.addView(input,inputLp);
            TextView error=promptText("",13,Color.rgb(190,55,55),false); settingsPromptError=error; error.setVisibility(View.GONE);
            LinearLayout.LayoutParams errorLp=new LinearLayout.LayoutParams(-1,-2); errorLp.topMargin=promptDp(6); card.addView(error,errorLp);
            LinearLayout buttons=new LinearLayout(this); buttons.setOrientation(LinearLayout.HORIZONTAL); buttons.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams buttonsLp=new LinearLayout.LayoutParams(-1,promptDp(48)); buttonsLp.topMargin=promptDp(16); card.addView(buttons,buttonsLp);
            TextView cancel=promptText("取消",15,Color.rgb(53,91,132),true); cancel.setGravity(Gravity.CENTER); cancel.setBackground(promptShape(Color.rgb(237,243,248),10)); cancel.setClickable(true); cancel.setFocusable(true);
            TextView confirm=promptText("确认",15,Color.WHITE,true); confirm.setGravity(Gravity.CENTER); confirm.setBackground(promptShape(Color.rgb(52,97,143),10)); confirm.setClickable(true); confirm.setFocusable(true); settingsPromptConfirm=confirm;
            LinearLayout.LayoutParams cancelLp=new LinearLayout.LayoutParams(0,-1,1); cancelLp.rightMargin=promptDp(10);
            buttons.addView(cancel,cancelLp); buttons.addView(confirm,new LinearLayout.LayoutParams(0,-1,1));
            cancel.setOnClickListener(v -> cancelSettingsPrompt());
            confirm.setOnClickListener(v -> {
                long wait=state.pinWaitMs();
                if(wait>0) { showPromptError("尝试过多，请稍后再试"); confirm.setEnabled(false); mainHandler.postDelayed(() -> { if(settingsPromptConfirm==confirm) confirm.setEnabled(true); },wait); return; }
                if(state.checkPin(input.getText().toString())) {
                    trace("password_overlay_accepted"); approveObservedSettingsAccess(); protectionPromptPending=false; removeSettingsPrompt();
                } else {
                    trace("password_overlay_rejected"); input.setText("");
                    long remaining=state.pinWaitMs();
                    if(remaining>0) { showPromptError("尝试过多，请等 30 秒"); confirm.setEnabled(false); mainHandler.postDelayed(() -> { if(settingsPromptConfirm==confirm) confirm.setEnabled(true); },remaining); }
                    else showPromptError("密码不正确");
                    input.requestFocus();
                }
            });
            android.widget.FrameLayout backdrop=new android.widget.FrameLayout(this);
            backdrop.setBackgroundColor(Color.argb(150,0,0,0)); backdrop.setClickable(true); backdrop.setFocusableInTouchMode(true);
            backdrop.setOnClickListener(v -> {});
            int screenWidth=getResources().getDisplayMetrics().widthPixels;
            int cardWidth=Math.min(promptDp(440),Math.max(promptDp(260),screenWidth-promptDp(36)));
            android.widget.FrameLayout.LayoutParams cardLp=new android.widget.FrameLayout.LayoutParams(cardWidth,-2,Gravity.CENTER);
            backdrop.addView(card,cardLp);
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
            params.gravity=Gravity.CENTER; params.softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
            windowManager.addView(backdrop,params); settingsPromptOverlay=backdrop;
            input.requestFocus(); trace("password_overlay_shown");
            mainHandler.postDelayed(() -> { if(settingsPromptOverlay==backdrop) {
                InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
                if(imm!=null) imm.showSoftInput(input,InputMethodManager.SHOW_IMPLICIT);
            } },250);
        } catch(RuntimeException e) {
            trace("password_overlay_failed="+e.getClass().getSimpleName()); protectionPromptPending=false;
            endSettingsAccess(); performGlobalAction(GLOBAL_ACTION_HOME);
            state.error("无法显示设置保护密码框，请检查无障碍服务状态");
        }
    }
    private void showPromptError(String message) {
        if(settingsPromptError!=null) { settingsPromptError.setText(message); settingsPromptError.setVisibility(View.VISIBLE); }
    }
    private void cancelSettingsPrompt() {
        trace("password_overlay_cancelled"); protectionPromptPending=false; endSettingsAccess(); removeSettingsPrompt();
        performGlobalAction(GLOBAL_ACTION_HOME);
    }
    private void removeSettingsPrompt() {
        View overlay=settingsPromptOverlay; settingsPromptOverlay=null;
        EditText input=settingsPromptInput; settingsPromptInput=null; settingsPromptError=null; settingsPromptConfirm=null;
        if(input!=null) {
            InputMethodManager imm=(InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(imm!=null) imm.hideSoftInputFromWindow(input.getWindowToken(),0);
        }
        if(overlay!=null && windowManager!=null) try { windowManager.removeViewImmediate(overlay); } catch(RuntimeException ignored) { }
    }
    private static final String PROTECTION_PREFS="watch_gate_settings_protection";
    public static boolean settingsProtectionEnabled(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(PROTECTION_PREFS,Context.MODE_PRIVATE)
            .getBoolean("enabled",false);
    }
    public static boolean setSettingsProtection(Context context,boolean enabled) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences(PROTECTION_PREFS,Context.MODE_PRIVATE)
            .edit().putBoolean("enabled",enabled).commit();
    }

    @Override protected void onServiceConnected() {
        active = this;
        windowManager=(WindowManager)getSystemService(WINDOW_SERVICE);
        trace("observer_connected");
        if (GateState.get(this).enabled()) GuardService.start(this);
    }

    /** 答题锁定刚出现时调用，避免下拉栏已经展开而没有新窗口事件。 */
    public static void dismissShadeIfLocked() {
        WindowObserver service = active;
        if (service != null) service.mainHandler.post(service::dismissShadeIfLockedOnMainThread);
    }

    private void dismissShadeIfLockedOnMainThread() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !GuardService.isQuizBlocking()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - lastShadeDismissMs < 250) return;
        lastShadeDismissMs = now;
        // Android 12+ 提供收起通知栏的系统动作；不读取系统界面内容，也不触发通用返回键。
        performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if(event!=null) {
            String eventPkg=event.getPackageName()==null?"":event.getPackageName().toString();
            if(eventPkg.isEmpty() || systemSettingsPackage(eventPkg) || eventPkg.equals(getPackageName()))
                trace("event type="+event.getEventType()+" source="+(eventPkg.isEmpty()?"none":eventPkg.equals(getPackageName())?"self":"settings"));
        }
        if (event == null || event.getPackageName() == null) return;
        int type = event.getEventType();
        String pkg = event.getPackageName().toString();
        if (pkg.equals("com.android.systemui")) {
            // 离开设置进入系统下拉面板后，下一次通过齿轮进入必须重新验证。
            if(settingsAuthorized && settingsSeen && settingsProtectionEnabled(this)) {
                trace("settings_access_ended_by_systemui"); endSettingsAccess();
            }
            if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                    && GuardService.isQuizBlocking()) dismissShadeIfLocked();
            return;
        }
        if(type!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && type!=AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        String penaltyNotice=null;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if(pkg.equals("com.android.settings")) {
                long now=SystemClock.elapsedRealtime();
                if(!settingsWindowSeen && now-lastSettingsPenaltyMs>1200 && GuardService.isQuizBlocking()
                        && !MainActivity.parentBypass() && !MainActivity.settingsBypass(pkg)) {
                    try {
                        int added=QuestionBank.get(this).addSettingsAttempt();
                        if(added>0) {
                            lastSettingsPenaltyMs=now; GuardService.refreshQuizRemaining();
                            String next=added==1?"下次再来依次添加3、5、9、16道题":added==3?"下次再来依次添加5、9、16道题":
                                added==5?"下次再来依次添加9、16道题":added==9?"下次再来添加16道题":"下次再来仍添加16道题";
                            penaltyNotice="恭喜，洋哥已为你添加 "+added+" 道题 😈\n"+next;
                        }
                    } catch(Exception e) { GateState.get(this).error("设置尝试加题失败，请检查设备存储"); }
                }
                settingsWindowSeen=true;
            } else if(!pkg.equals("android")) settingsWindowSeen=false;
        }
        noteSettingsWindow(pkg);
        if(systemSettingsPackage(pkg)) trace("settings_gate enabled="+settingsProtectionEnabled(this)
            +" pin="+GateState.get(this).hasPin()+" bypass="+MainActivity.settingsBypass(pkg)+" pending="+protectionPromptPending);
        // 用无障碍专属遮罩显示验证框，避免华为拦截从后台启动 Activity。
        if(systemSettingsPackage(pkg) && settingsProtectionEnabled(this)
                && GateState.get(this).hasPin() && !MainActivity.settingsBypass(pkg)) {
            if(!protectionPromptPending || settingsPromptOverlay==null) {
                if(protectionPromptPending) trace("stale_prompt_pending_cleared");
                protectionPromptPending=true;
                trace("password_overlay_requested");
                final String noticeForPrompt=penaltyNotice;
                mainHandler.post(() -> showSettingsPrompt(noticeForPrompt));
            }
            return;
        }
        if(type==AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if(penaltyNotice!=null) android.widget.Toast.makeText(this,penaltyNotice,android.widget.Toast.LENGTH_LONG).show();
            // 自己的悬浮窗不是应用切换，避免答题窗出现后立刻被误判隐藏。
            if (pkg.equals(getPackageName()) && !MainActivity.visible) return;
            // 输入法、音量面板等窗口不代表切换应用。快速识别只补充目标应用，
            // 离开目标应用由使用情况事件判断，避免系统浮层把计时误停。
            if (GateState.get(this).targets().contains(pkg)) GuardService.noteWindow(pkg);
        }
    }

    @Override public void onDestroy() {
        if (active == this) active = null;
        removeSettingsPrompt(); endSettingsAccess(); protectionPromptPending=false;
        super.onDestroy();
    }

    @Override public void onInterrupt() { }
}
