package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** 全局状态只存本机；使用开机解锁前可读的存储，重启不会重置额度。 */
public final class GateState {
    private static GateState instance;
    private final SharedPreferences prefs;
    public static synchronized GateState get(Context context) {
        if (instance == null) instance = new GateState(context);
        return instance;
    }
    private GateState(Context context) {
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_v1", Context.MODE_PRIVATE);
    }
    public boolean hasPin() { return prefs.contains("pin_hash"); }
    public boolean enabled() { return hasPin() && prefs.getBoolean("enabled", false); }
    public boolean controlActive() { return hasPin() && (enabled() || scheduledActive() || prefs.getBoolean("schedule_hold",false)); }
    public boolean scheduledActive() { return !prefs.getString("schedule_window","").isEmpty(); }
    public boolean scheduledHolding() { return prefs.getBoolean("schedule_hold",false); }
    public int activeRoundQuestions() {
        if(prefs.getBoolean("schedule_due",false)) return prefs.getInt("schedule_due_questions",1);
        return scheduledActive()?prefs.getInt("schedule_questions",1):roundQuestions();
    }
    public synchronized boolean syncScheduleWindow(String window,int questions) {
        String previous=prefs.getString("schedule_window","");
        boolean hold=prefs.getBoolean("schedule_hold",false),due=prefs.getBoolean("schedule_due",false);
        if(previous.equals(window) && (window.isEmpty() || prefs.getInt("schedule_questions",1)==questions)) return true;
        SharedPreferences.Editor edit=prefs.edit();
        if(!previous.equals(window)) {
            // 已经弹出的定时题目跨越时段结束后仍须完成。
            if(!previous.isEmpty() && due && locked()) hold=true;
            if(!window.isEmpty() && !enabled() && !hold) edit.putLong("used_ms",0);
            if(window.isEmpty() && !enabled() && !hold) edit.putLong("used_ms",0);
            if(!hold) { due=false; edit.putBoolean("schedule_due",false); }
        }
        return edit.putString("schedule_window",window).putInt("schedule_questions",questions)
            .putBoolean("schedule_hold",hold).commit();
    }
    public synchronized boolean markScheduledQuestionDue() {
        if(!scheduledActive() || prefs.getBoolean("schedule_due",false)) return true;
        return prefs.edit().putBoolean("schedule_due",true)
            .putInt("schedule_due_questions",prefs.getInt("schedule_questions",1)).commit();
    }
    public long limit() { return prefs.getLong("limit_ms", 30 * 60_000L); }
    public long used() { return prefs.getLong("used_ms", 0); }
    public boolean quizTest() { return prefs.getBoolean("quiz_test",false); }
    public boolean locked() { return quizTest() || used() >= limit(); }
    public int level() { return prefs.getInt("level", 1); }
    public int roundQuestions() { return Math.max(1,Math.min(20,prefs.getInt("round_questions",1))); }
    public synchronized boolean setRoundQuestions(int value) {
        return value>=1 && value<=20 && prefs.edit().putInt("round_questions",value).commit();
    }
    public boolean demo() { return prefs.getBoolean("demo", false); }
    public Set<String> whitelist() { return new HashSet<>(prefs.getStringSet("app_whitelist", new HashSet<>())); }
    public Set<String> blacklist() { return new HashSet<>(prefs.getStringSet("app_blacklist", new HashSet<>())); }
    public boolean isWhitelisted(String pkg) { return pkg != null && whitelist().contains(pkg); }
    public boolean isBlacklisted(String pkg) { return pkg != null && blacklist().contains(pkg); }
    public boolean isBlacklistAllowed(String pkg) {
        return pkg != null && pkg.equals(prefs.getString("blacklist_allowed_package", ""));
    }
    public synchronized boolean setAppRules(Set<String> allow, Set<String> block) {
        if (allow.size() > 200 || block.size() > 200 || allow.contains("cn.tan.watchgate")) return false;
        for (String pkg : allow) if (block.contains(pkg)) return false;
        return prefs.edit().putStringSet("app_whitelist", new HashSet<>(allow))
            .putStringSet("app_blacklist", new HashSet<>(block)).remove("blacklist_allowed_package").commit();
    }
    public synchronized boolean allowBlacklistApp(String pkg) {
        return pkg != null && !pkg.isEmpty() && prefs.edit().putString("blacklist_allowed_package", pkg).commit();
    }
    public synchronized void revokeBlacklistApp(String pkg) {
        if (pkg != null && pkg.equals(prefs.getString("blacklist_allowed_package", "")))
            prefs.edit().remove("blacklist_allowed_package").commit();
    }
    public synchronized void revokeBlacklistAppWhenDifferent(String foregroundPkg) {
        String allowed=prefs.getString("blacklist_allowed_package", "");
        if(!allowed.isEmpty() && !allowed.equals(foregroundPkg)) prefs.edit().remove("blacklist_allowed_package").commit();
    }
    public synchronized void clearBlacklistAllowance() { prefs.edit().remove("blacklist_allowed_package").commit(); }
    public synchronized String webKey() {
        String key=prefs.getString("web_key", "");
        if(key.isEmpty()) {
            byte[] bytes=new byte[24]; new SecureRandom().nextBytes(bytes);
            key=Base64.encodeToString(bytes,Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if(!prefs.edit().putString("web_key",key).commit()) return "";
        }
        return key;
    }
    // 家人分享链接使用独立凭证，避免把完整家长端密钥放进链接。
    public synchronized String familyShareKey() { return prefs.getString("family_share_key",""); }
    public synchronized String createFamilyShareKey() {
        String key=familyShareKey();
        if(!key.isEmpty()) return key;
        byte[] bytes=new byte[24]; new SecureRandom().nextBytes(bytes);
        key=Base64.encodeToString(bytes,Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        return prefs.edit().putString("family_share_key",key).commit()?key:"";
    }
    public synchronized boolean checkFamilyShareKey(String candidate) {
        String key=familyShareKey();
        return !key.isEmpty() && candidate!=null && MessageDigest.isEqual(
            key.getBytes(java.nio.charset.StandardCharsets.UTF_8),candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    public synchronized boolean revokeFamilyShareKey() { return prefs.edit().remove("family_share_key").commit(); }
    // 分享页的放行次数按儿童手机本地日期计算。
    private String familyReleaseDay() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd",java.util.Locale.ROOT).format(new java.util.Date());
    }
    public synchronized int familyReleaseRemaining() {
        String today=familyReleaseDay();
        if(!today.equals(prefs.getString("family_release_day",""))) {
            if(!prefs.edit().putString("family_release_day",today).putInt("family_release_count",0).commit()) return 0;
            return 3;
        }
        return Math.max(0,3-prefs.getInt("family_release_count",0));
    }
    public synchronized boolean recordFamilyRelease() {
        String today=familyReleaseDay();
        int count=today.equals(prefs.getString("family_release_day",""))?prefs.getInt("family_release_count",0):0;
        if(count>=3) return false;
        return prefs.edit().putString("family_release_day",today).putInt("family_release_count",count+1).commit();
    }
    public synchronized String issuePairCode() {
        // 家长确认后生成短期一次性验证码，连续输错五次立即失效。
        String code=String.format(java.util.Locale.ROOT,"%06d",new SecureRandom().nextInt(1_000_000));
        return prefs.edit().putString("pair_code",code).putLong("pair_expires",System.currentTimeMillis()+300_000)
            .putInt("pair_attempts",0).commit() ? code : "";
    }
    public synchronized boolean consumePairCode(String candidate) {
        String code=prefs.getString("pair_code", "");
        if(code.isEmpty() || System.currentTimeMillis()>prefs.getLong("pair_expires",0) || prefs.getInt("pair_attempts",0)>=5) return false;
        if(MessageDigest.isEqual(code.getBytes(java.nio.charset.StandardCharsets.UTF_8),candidate.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return prefs.edit().remove("pair_code").remove("pair_expires").remove("pair_attempts").commit();
        }
        int failures=prefs.getInt("pair_attempts",0)+1;
        SharedPreferences.Editor edit=prefs.edit().putInt("pair_attempts",failures);
        if(failures>=5) edit.remove("pair_code").remove("pair_expires");
        edit.commit(); return false;
    }
    public synchronized boolean lockNow() { return prefs.edit().putLong("used_ms",limit()).putBoolean("enabled",true).commit(); }
    public Set<String> targets() {
        return new HashSet<>(prefs.getStringSet("targets", new HashSet<>(Arrays.asList("com.ktcp.video", "com.tencent.qqlive"))));
    }
    public String error() { return prefs.getString("error", ""); }
    public void error(String value) { prefs.edit().putString("error", value).apply(); }
    public long heartbeat() { return prefs.getLong("monitor_heartbeat", 0); }
    public String backgroundSummary() { return prefs.getString("monitor_background", "尚无后台运行记录，请打开所选应用"); }
    public void recordMonitor(String reason, String pkg, boolean background) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit().putLong("monitor_heartbeat",now);
        if (background) {
            String time = new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.ROOT).format(new java.util.Date(now));
            edit.putString("monitor_background",time+" · "+reason+"\n识别应用："+(pkg.isEmpty()?"暂无":pkg)+"\n当时剩余："+Ui.duration(limit()-used()));
        }
        edit.apply();
    }
    public synchronized boolean enable(boolean value) { return prefs.edit().putBoolean("enabled", value).commit(); }
    public synchronized boolean setTargets(Set<String> value) { return prefs.edit().putStringSet("targets", new HashSet<>(value)).commit(); }
    public synchronized boolean setLevel(int value) { return prefs.edit().putInt("level", value).commit(); }
    public synchronized boolean setLimit(long value) {
        // 修改时长会开始新周期，只允许通过家长验证后的设置入口调用。
        return prefs.edit().putLong("limit_ms", value).putLong("used_ms", 0).putBoolean("demo", false).commit();
    }
    public synchronized boolean setLimitKeepingUsage(long value) {
        // 滑块只调整本轮上限，不清空已经用掉的时间。
        if(value<60_000L || value>120*60_000L) return false;
        return prefs.edit().putLong("limit_ms",value).putBoolean("demo",false).commit();
    }
    public synchronized boolean startDemo() {
        long restore = demo() ? prefs.getLong("demo_restore_limit", 30 * 60_000L) : limit();
        return prefs.edit().putLong("demo_restore_limit", restore).putBoolean("demo", true)
            .putLong("limit_ms", 30_000).putLong("used_ms", 0).commit();
    }
    public synchronized boolean startQuizTest() {
        if(quizTest()) return true;
        // 原本已经到时则沿用正式锁定，避免测试遮盖真实的到时状态。
        if(used()>=limit()) return prefs.edit().putBoolean("enabled",true).commit();
        return prefs.edit().putBoolean("quiz_test",true)
            .putBoolean("quiz_test_previous_enabled",prefs.getBoolean("enabled",false))
            .putBoolean("enabled",true).commit();
    }
    public synchronized boolean reserve(long milliseconds) {
        // 先把下一小段额度落盘，再允许使用；突然断电最多提前扣 1 秒，不会获赠时间。
        return prefs.edit().putLong("used_ms", Math.min(limit(), used() + milliseconds)).commit();
    }
    public synchronized boolean unlock() {
        if(quizTest()) {
            // 测试答对或家长放行只结束测试，不清零正式使用时间。
            return prefs.edit().putBoolean("enabled",prefs.getBoolean("quiz_test_previous_enabled",false))
                .remove("quiz_test").remove("quiz_test_previous_enabled")
                .putBoolean("schedule_due",false).putBoolean("schedule_hold",false).commit();
        }
        SharedPreferences.Editor edit = prefs.edit().putLong("used_ms", 0)
            .putBoolean("schedule_due",false).putBoolean("schedule_hold",false);
        if (demo()) edit.putBoolean("demo", false).putLong("limit_ms", prefs.getLong("demo_restore_limit", 30 * 60_000L));
        return edit.commit();
    }
    public long pinWaitMs() { return Math.max(0, prefs.getLong("pin_wait_until", 0) - System.currentTimeMillis()); }
    public boolean setPin(String pin) {
        try {
            byte[] salt = new byte[24]; new SecureRandom().nextBytes(salt);
            return prefs.edit().putString("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString("pin_hash", Base64.encodeToString(derive(pin, salt), Base64.NO_WRAP))
                .putInt("pin_failures", 0).putLong("pin_wait_until", 0).commit();
        } catch (Exception e) { error("无法保存家长密码，请重试"); return false; }
    }
    public synchronized boolean checkPin(String pin) {
        if (pinWaitMs() > 0 || !hasPin()) return false;
        try {
            byte[] salt = Base64.decode(prefs.getString("pin_salt", ""), Base64.NO_WRAP);
            byte[] hash = Base64.decode(prefs.getString("pin_hash", ""), Base64.NO_WRAP);
            if (MessageDigest.isEqual(hash, derive(pin, salt))) {
                prefs.edit().putInt("pin_failures", 0).putLong("pin_wait_until", 0).commit(); return true;
            }
        } catch (Exception ignored) { }
        int failures = prefs.getInt("pin_failures", 0) + 1;
        prefs.edit().putInt("pin_failures", failures)
            .putLong("pin_wait_until", failures >= 5 ? System.currentTimeMillis() + 30_000 : 0).commit();
        return false;
    }
    private static byte[] derive(String pin, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 65_536, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }
}
