package cn.tan.watchgate;

import android.app.*;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import android.provider.Settings;

/** 在独立线程计时，UI 线程只负责显示答题页，避免存盘阻塞画面。 */
public final class GuardService extends Service {
    private static volatile GuardService running;
    private HandlerThread thread;
    private Handler worker;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private GateState state;
    private QuizOverlay overlay;
    private PracticePopup practicePopup;
    private WebAdmin web;
    private volatile String foreground = "";
    private String foregroundActivity = "";
    private long topWall, previousWall, lastTick, credit;
    private long lastNotification;
    private long reminderPrevious=-1, reminderUsed=-1, reminderLimit=-1;
    private int reminderMask;
    private android.view.WindowManager reminderWindows;
    private android.view.View reminderView;
    private Runnable reminderDismiss;
    private boolean initialQuery = true, storageFailed, wasWatching;
    private volatile boolean blocking;
    public boolean isBlocking() { return blocking; }
    public static boolean isQuizBlocking() {
        GuardService s = running;
        return s != null && s.blocking && !MainActivity.parentBypass()
            && !MainActivity.settingsBypass(s.foreground) && !s.isEmergency(s.foreground);
    }
    public static boolean isRunning() { return running != null; }
    public static void start(Context context) {
        try { context.startForegroundService(new Intent(context, GuardService.class)); }
        catch (RuntimeException e) { GateState.get(context).error("系统未允许后台恢复，请重新启动应用（" + e.getClass().getSimpleName() + "）"); }
    }
    public static void refresh() {
        GuardService s = running;
        if (s != null && s.worker != null) s.worker.post(() -> { s.credit = 0; s.lastTick = SystemClock.elapsedRealtime(); s.safeTick(); });
    }
    public static void refreshQuizRemaining() {
        GuardService s=running;
        if(s!=null && s.overlay!=null) s.ui.post(s.overlay::refreshRemaining);
    }
    public static void hidePracticePopup() {
        GuardService s=running;
        if(s!=null&&s.practicePopup!=null) s.ui.post(s.practicePopup::hide);
    }
    public static void releaseBlacklistCurrent() {
        GuardService s=running;
        if(s!=null && s.state.isBlacklisted(s.foreground)) s.state.allowBlacklistApp(s.foreground);
    }
    public static void noteWindow(String pkg) {
        GuardService s = running;
        if (s == null || pkg == null) return;
        // 不读取窗口内容，仅使用系统给出的应用包名。
        if (pkg.equals("com.android.systemui")) return;
        s.worker.post(() -> { s.foreground = pkg; s.foregroundActivity = ""; s.topWall = System.currentTimeMillis(); s.safeTick(); });
    }
    @Override public void onCreate() {
        super.onCreate(); state = GateState.get(this); state.clearBlacklistAllowance();
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("parental_guard", "洋哥做题了", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("保持本机计时运行；点击进入家长设置"); manager.createNotificationChannel(channel);
        Notification notification = notification("正在恢复后台计时");
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(7, notification);
        overlay = new QuizOverlay(this); practicePopup=new PracticePopup(this);
        web = new WebAdmin(this); web.start();
        thread = new HandlerThread("watch-gate-clock"); thread.start(); worker = new Handler(thread.getLooper());
        running = this; lastTick = SystemClock.elapsedRealtime();
        worker.post(clock);
    }
    private final Runnable clock = new Runnable() {
        @Override public void run() {
            safeTick();
            if (running == GuardService.this) worker.postDelayed(this, 1000);
        }
    };
    private Notification notification(String reason) {
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"parental_guard").setSmallIcon(R.drawable.icon)
            .setContentTitle("洋哥做题了 · 剩余 "+Ui.duration(state.limit()-state.used()))
            .setContentText(reason).setStyle(new Notification.BigTextStyle().bigText(reason+"\n识别应用："+(foreground.isEmpty()?"暂无":foreground)))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).build();
    }
    private void report(String reason) {
        state.recordMonitor(reason,foreground,!MainActivity.visible);
        long now = SystemClock.elapsedRealtime();
        if(now-lastNotification >= 1000) {
            lastNotification=now;
            try { getSystemService(NotificationManager.class).notify(7,notification(reason)); }
            catch(RuntimeException ignored) { /* 通知权限被拒绝不应中断计时。 */ }
        }
    }
    private void safeTick() {
        try { tick(); }
        catch(RuntimeException e) {
            blocking=false;
            state.error("后台计时异常："+e.getClass().getSimpleName()+"，请把此提示发给开发者");
            report("计时异常，正在重试");
            ui.post(() -> overlay.hide());
        }
        // 题目锁定或刷题开始后，后台中的拍题结果也不能继续显示。
        try {if(!PhotoTutorAccess.allowed(this))PhotoTutorSession.get(this).cancelForRestriction();}
        catch(RuntimeException ignored){PhotoTutorSession.get(this).cancelForRestriction();}
    }
    private void queryForeground(long now) {
        if (!MainActivity.hasUsageAccess(this)) return;
        try {
            if (now < previousWall || now - previousWall > 120_000) { initialQuery = true; topWall = 0; }
            UsageStatsManager manager = (UsageStatsManager) getSystemService(USAGE_STATS_SERVICE);
            UsageEvents events = manager.queryEvents(now - (initialQuery ? 86_400_000 : 15_000), now + 1);
            initialQuery = false;
            if (events == null) return;
            UsageEvents.Event event = new UsageEvents.Event();
            while (events.hasNextEvent()) {
                events.getNextEvent(event);
                if (event.getTimeStamp() < topWall) continue;
                if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    state.revokeBlacklistAppWhenDifferent(event.getPackageName());
                    foreground = event.getPackageName(); foregroundActivity=event.getClassName(); topWall = event.getTimeStamp();
                } else if (event.getEventType() == UsageEvents.Event.MOVE_TO_BACKGROUND) {
                    if (foreground.equals(event.getPackageName()) && !foregroundActivity.isEmpty()
                        && foregroundActivity.equals(event.getClassName())) {
                        // Android 10+ 按页面发送暂停事件。旧页面暂停不能清除已恢复的新页面。
                        state.revokeBlacklistApp(event.getPackageName());
                        foreground = ""; foregroundActivity=""; topWall = event.getTimeStamp();
                    }
                }
            }
        } catch (RuntimeException e) { state.error("无法读取应用使用情况，请检查使用情况访问权限"); }
        finally { previousWall = now; }
    }
    /** 家长下发后用悬浮窗直接显示刷题题目；熄屏或锁定时等设备解锁再显示。 */
    private void deliverPractice() {
        if(!PracticeBank.get(this).enabled()||PracticeActivity.visible||MainActivity.parentBypass()
                ||MainActivity.settingsBypass(foreground)||isEmergency(foreground)) {
            ui.post(practicePopup::hide); return;
        }
        if(!((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()||((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked()) {
            ui.post(practicePopup::hide); return;
        }
        ui.post(()->{
            if(!PracticeBank.get(this).enabled()||PracticeActivity.visible||MainActivity.parentBypass()) { practicePopup.hide(); return; }
            overlay.hide(); practicePopup.show();
        });
    }
    private void tick() {
        if (worker == null) return;
        long elapsedNow = SystemClock.elapsedRealtime();
        long delta = Math.max(0, elapsedNow - lastTick); lastTick = elapsedNow;
        ScheduleManager.tick(this,state);
        // 题库有库存缺口时在后台提前准备，避免弹题窗口临时等生成。
        QuestionBank.get(this).requestAutoSupply(false);
        deliverPractice();
        if(ScheduleManager.isPaused(this,System.currentTimeMillis())) {
            wasWatching=false; blocking=false; credit=0; reminderPrevious=-1;
            report("定时暂停中，将按设定时间恢复"); ui.post(() -> overlay.hide()); return;
        }
        if (!MainActivity.hasUsageAccess(this)) {
            credit = 0; wasWatching = false; blocking = false;
            state.error("使用情况访问权限未开启，当前无法识别受管控应用");
            report("使用情况访问权限未开启"); ui.post(() -> overlay.hide()); return;
        }
        queryForeground(System.currentTimeMillis());
        boolean screenOn = ((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()
            && !((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();
        // 家长页、系统设置、紧急电话和白名单应用不触发拦截。
        boolean emergency = isEmergency(foreground);
        boolean parentMode = MainActivity.parentBypass();
        boolean settingsMode = MainActivity.settingsBypass(foreground);
        boolean whitelisted = state.isWhitelisted(foreground) && !state.quizTest();
        // 刷题模式使用独立悬浮窗，不与普通计时答题窗重叠。
        if(screenOn&&PracticeBank.get(this).enabled()&&!parentMode&&!settingsMode&&!emergency) {
            wasWatching=false;blocking=false;credit=0;reminderPrevious=-1;
            report("儿童端刷题中，普通计时暂停");ui.post(overlay::hide);return;
        }
        if(screenOn&&PracticeActivity.visible) {
            wasWatching=false;blocking=false;credit=0;reminderPrevious=-1;
            report("儿童端刷题中，普通计时暂停");ui.post(overlay::hide);return;
        }
        if (!screenOn || parentMode || settingsMode || emergency) {
            wasWatching = false; blocking = false; credit = 0; reminderPrevious=-1;
            report(!screenOn ? "熄屏或锁屏，暂停计时" : emergency ? "电话界面，暂停计时" : "家长设置中，暂停计时");
            ui.post(() -> overlay.hide()); return;
        }
        QuestionBank bank=QuestionBank.get(this);
        long loanRemaining=bank.loanRemainingMs();
        if(state.controlActive() && loanRemaining>0) {
            // 贷款按实际时间到期；欠题仍保存在原会话。
            wasWatching=false; blocking=false; credit=0; reminderPrevious=-1;
            report("已借用时间，剩余 "+Ui.duration(loanRemaining));
            ui.post(() -> overlay.hide()); return;
        }
        boolean loanDebt=bank.loanDebtDue();
        boolean blacklistChallenge = !whitelisted && state.isBlacklisted(foreground)
            && !state.isBlacklistAllowed(foreground);
        if (blacklistChallenge) {
            try { QuestionBank.get(this).beginBlacklistSession(state.level(),foreground,50); }
            catch (Exception e) { state.error("黑名单答题进度无法保存，请检查设备存储"); }
        }
        boolean watchingTime = state.controlActive() && !whitelisted;
        if (watchingTime && wasWatching && credit > 0) {
            long overflow = Math.max(0,delta-credit);
            credit = Math.max(0, credit - delta);
            // 工作线程短暂延迟时补扣真实经过的时间，不能只扣一次心跳。
            if (overflow > 0 && !state.locked() && !state.reserve(overflow)) storageFailed = true;
        }
        if (!watchingTime) { wasWatching = false; credit = 0; }
        else wasWatching = true;
        // 贷款到期必须继续完成原会话，不能通过切换到白名单应用跳过欠题。
        boolean timeBlocked = state.controlActive() && (loanDebt || (watchingTime && ((state.locked() && credit == 0) || storageFailed)));
        if (watchingTime && !timeBlocked && credit == 0) {
            long allocation = Math.min(1000, state.limit() - state.used());
            if (allocation > 0 && state.reserve(allocation)) credit = allocation;
            else { storageFailed = true; state.error("计时状态保存失败，请由家长检查设备存储空间"); timeBlocked = true; }
        }
        boolean blocked = blacklistChallenge || timeBlocked || (watchingTime && storageFailed);
        if(timeBlocked && state.locked() && !state.markScheduledQuestionDue()) state.error("定时答题状态保存失败");
        if(watchingTime && !blocked) updateCountdownReminder();
        else reminderPrevious=-1;
        boolean wasBlocking = blocking;
        blocking = blocked;
        if (blocked && !wasBlocking) WindowObserver.dismissShadeIfLocked();
        report(blacklistChallenge ? "黑名单应用已打开，正在进行答题挑战" : timeBlocked ? "本轮已到时，正在显示答题窗" : whitelisted ? "白名单应用，暂停计时" : !state.controlActive() ? "计时已暂停" : "后台计时中");
        final boolean show = blocked;
        ui.post(() -> { if (show) overlay.show(); else overlay.hide(); });
    }
    /** 只在正常计时跨过提醒点时提示，同一轮每个提醒点最多出现一次。 */
    private void updateCountdownReminder() {
        long used=state.used(), limit=state.limit();
        long remaining=Math.max(0,limit-used+credit);
        if(reminderUsed>=0 && (used<reminderUsed || limit!=reminderLimit)) {
            reminderMask=0; reminderPrevious=-1;
        }
        reminderUsed=used; reminderLimit=limit;
        if(reminderPrevious<0) { reminderPrevious=remaining+1000; }
        int[] seconds={30,10,3,2,1}; int show=0;
        for(int i=0;i<seconds.length;i++) {
            long threshold=seconds[i]*1000L;
            if((reminderMask&(1<<i))==0 && reminderPrevious>threshold && remaining<=threshold && remaining>0) {
                reminderMask|=1<<i; show=seconds[i];
            }
        }
        reminderPrevious=remaining;
        if(show>0) { final int secondsLeft=show; ui.post(() -> showCountdownReminder(secondsLeft)); }
    }
    private void showCountdownReminder(int seconds) {
        String message="本轮使用时间还剩 "+seconds+" 秒";
        hideCountdownReminder();
        try {
            if(reminderWindows==null) reminderWindows=(android.view.WindowManager)getSystemService(WINDOW_SERVICE);
            boolean showImage=seconds==30 || seconds==10;
            android.view.WindowManager.LayoutParams params=new android.view.WindowManager.LayoutParams(-2,-2,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
            if(showImage) {
                android.widget.ImageView image=new android.widget.ImageView(this);
                try(java.io.InputStream in=getAssets().open("countdown_reminder.png")) {
                    android.graphics.drawable.Drawable drawable=android.graphics.drawable.Drawable.createFromStream(in,null);
                    if(drawable==null) throw new java.io.IOException("图片无法读取");
                    image.setImageDrawable(drawable);
                }
                image.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
                params.width=Math.max(1,Math.min(Ui.dp(this,420),metrics.widthPixels-Ui.dp(this,48)));
                params.height=Math.max(1,Math.min(Ui.dp(this,420),metrics.heightPixels-Ui.dp(this,96)));
                params.gravity=android.view.Gravity.CENTER;
                reminderView=image;
            } else {
                android.widget.TextView label=Ui.text(this,message,18,android.graphics.Color.WHITE);
                label.setGravity(android.view.Gravity.CENTER);
                label.setPadding(Ui.dp(this,18),Ui.dp(this,10),Ui.dp(this,18),Ui.dp(this,10));
                label.setBackground(Ui.shape(this,android.graphics.Color.rgb(44,59,86),0));
                params.gravity=android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL; params.y=Ui.dp(this,64);
                reminderView=label;
            }
            reminderWindows.addView(reminderView,params);
            reminderDismiss=() -> hideCountdownReminder();
            ui.postDelayed(reminderDismiss,showImage?2000:850);
        } catch(Exception e) {
            hideCountdownReminder();
            android.widget.Toast.makeText(this,message,android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void hideCountdownReminder() {
        if(reminderDismiss!=null) ui.removeCallbacks(reminderDismiss);
        reminderDismiss=null;
        if(reminderView!=null) { try { reminderWindows.removeView(reminderView); } catch(RuntimeException ignored) { } reminderView=null; }
    }
    public void released() {
        blocking = false;
        worker.post(() -> { credit = 0; storageFailed = false; lastTick = SystemClock.elapsedRealtime(); });
    }
    private boolean isEmergency(String pkg) {
        if(pkg.equals("com.android.incallui") || pkg.equals("com.android.phone") || pkg.equals("com.android.emergency") || pkg.equals("com.huawei.emergency")) return true;
        try {
            android.telecom.TelecomManager telecom=(android.telecom.TelecomManager)getSystemService(TELECOM_SERVICE);
            String dialer=telecom==null?null:telecom.getDefaultDialerPackage();
            return dialer!=null && dialer.equals(pkg);
        } catch(RuntimeException ignored) { return false; }
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
    @Override public void onDestroy() {
        running = null;
        if (worker != null) worker.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (overlay != null) overlay.hide();
        if (practicePopup != null) practicePopup.hide();
        hideCountdownReminder();
        if (web != null) web.close();
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
