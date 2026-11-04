package cn.tan.watchgate;

import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.*;

/** 按系统事件重建前台时段，跨午夜裁剪，熄屏、锁屏、关机时不累计。 */
public final class UsageReport {
    public static final class Entry {
        public final String pkg, label;
        public final long milliseconds;
        Entry(String pkg,String label,long value) { this.pkg=pkg; this.label=label; milliseconds=value; }
    }
    public static List<Entry> today(Context context,Set<String> launchable) {
        return today(context,launchable,Collections.emptySet());
    }
    public static List<Entry> today(Context context,Set<String> launchable,Set<String> excluded) {
        long now = System.currentTimeMillis();
        Calendar day = Calendar.getInstance(); day.setTimeInMillis(now);
        day.set(Calendar.HOUR_OF_DAY,0); day.set(Calendar.MINUTE,0); day.set(Calendar.SECOND,0); day.set(Calendar.MILLISECOND,0);
        long midnight = day.getTimeInMillis(), begin = midnight-86_400_000;
        UsageStatsManager manager = (UsageStatsManager)context.getSystemService(Context.USAGE_STATS_SERVICE);
        UsageEvents events = manager.queryEvents(begin,now);
        if (events == null) return Collections.emptyList();
        Map<String,Long> totals = new HashMap<>();
        String active = null; boolean screen = true, keyguard = false; long cursor = begin;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event); long time = Math.min(now,event.getTimeStamp());
            if (time < cursor) continue;
            int type = event.getEventType();
            // DEVICE_STARTUP=27。突然断电缺少关机事件时，保守排除不明的跨开机间隔。
            if (active != null && screen && !keyguard && type != 27) add(totals,active,Math.max(cursor,midnight),time);
            if (type == UsageEvents.Event.MOVE_TO_FOREGROUND) active = event.getPackageName();
            else if (type == UsageEvents.Event.MOVE_TO_BACKGROUND && Objects.equals(active,event.getPackageName())) active = null;
            else if (type == 15) screen = true;
            else if (type == 16) screen = false;
            else if (type == 17) keyguard = true;
            else if (type == 18) keyguard = false;
            else if (type == 26 || type == 27) { active = null; screen = type == 27; keyguard = false; }
            cursor = time;
        }
        if (active != null && screen && !keyguard) add(totals,active,Math.max(cursor,midnight),now);
        // 未使用但可以从桌面启动的应用也展示，方便家长看到完整应用清单。
        for(String pkg:launchable) if(!excluded.contains(pkg) && !totals.containsKey(pkg)) totals.put(pkg,0L);
        PackageManager packages = context.getPackageManager(); List<Entry> result = new ArrayList<>();
        for(Map.Entry<String,Long> item:totals.entrySet()) {
            String pkg=item.getKey(), label=pkg;
            if(excluded.contains(pkg)) continue;
            try { label=packages.getApplicationLabel(packages.getApplicationInfo(pkg,0)).toString(); }
            catch(PackageManager.NameNotFoundException ignored) { }
            result.add(new Entry(pkg,label,item.getValue()));
        }
        result.sort((a,b) -> a.milliseconds != b.milliseconds ? Long.compare(b.milliseconds,a.milliseconds) : a.label.compareToIgnoreCase(b.label));
        return result;
    }
    private static void add(Map<String,Long> totals,String pkg,long begin,long end) {
        if (end>begin) totals.put(pkg,totals.getOrDefault(pkg,0L)+end-begin);
    }
    public static String format(long value) {
        long seconds=Math.max(0,value)/1000, minutes=seconds/60, hours=minutes/60;
        if(hours>0) return hours+" 小时 "+(minutes%60)+" 分钟";
        if(minutes>0) return minutes+" 分钟 "+(seconds%60)+" 秒";
        return seconds+" 秒";
    }
}
