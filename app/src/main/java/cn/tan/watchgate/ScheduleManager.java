package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.UUID;

/** 家长设定的本地重复任务；不改变家长手动开关的原有状态。 */
public final class ScheduleManager {
    private static long lastEvaluated, lastPersisted;
    private ScheduleManager() { }
    private static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_schedules",Context.MODE_PRIVATE);
    }
    public static synchronized JSONArray list(Context context) throws Exception {
        return new JSONArray(prefs(context).getString("tasks","[]"));
    }
    private static int minutes(String value) {
        if(value==null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) return -1;
        return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3,5));
    }
    private static boolean includesDay(JSONArray days,int day) {
        if(days==null) return false;
        for(int i=0;i<days.length();i++) if(days.optInt(i,-1)==day) return true;
        return false;
    }
    public static synchronized void save(Context context,JSONArray tasks) throws Exception {
        if(tasks==null || tasks.length()>30) throw new IllegalArgumentException("最多设置 30 条任务");
        JSONArray clean=new JSONArray(); java.util.HashSet<String> ids=new java.util.HashSet<>();
        for(int i=0;i<tasks.length();i++) {
            JSONObject item=tasks.getJSONObject(i); String id=item.optString("id","");
            if(id.isEmpty()) id=UUID.randomUUID().toString();
            String type=item.optString("type"),start=item.optString("time"),end=item.optString("end","");
            JSONArray days=item.optJSONArray("days");
            if(!id.matches("[A-Za-z0-9_-]{1,64}") || !ids.add(id) || !(type.equals("quiz")||type.equals("quiz-window")||type.equals("practice")||type.equals("pause")) || minutes(start)<0 || days==null || days.length()<1 || days.length()>7)
                throw new IllegalArgumentException("任务时间或重复日期无效");
            boolean[] seen=new boolean[8]; JSONArray cleanDays=new JSONArray();
            for(int j=0;j<days.length();j++) { int day=days.optInt(j,-1); if(day<1||day>7||seen[day]) throw new IllegalArgumentException("重复日期无效"); seen[day]=true; cleanDays.put(day); }
            boolean window=type.equals("pause")||type.equals("quiz-window");
            if(window && (minutes(end)<0 || end.equals(start))) throw new IllegalArgumentException("开始和结束时间不能相同");
            int questions=item.optInt("questions",0);
            if(type.equals("quiz-window") && (questions<1 || questions>20)) throw new IllegalArgumentException("每轮题数应为 1 到 20");
            JSONObject task=new JSONObject().put("id",id).put("type",type).put("time",start).put("end",window?end:"")
                .put("days",cleanDays).put("enabled",item.optBoolean("enabled",true));
            if(type.equals("quiz-window")) task.put("questions",questions);
            clean.put(task);
        }
        long now=System.currentTimeMillis();
        if(!prefs(context).edit().putString("tasks",clean.toString()).putLong("last_eval",now).commit()) throw new IllegalStateException("定时任务保存失败");
        lastEvaluated=now; lastPersisted=now;
    }
    public static synchronized boolean isPaused(Context context,long now) {
        try {
            Calendar today=Calendar.getInstance(); today.setTimeInMillis(now);
            int current=today.get(Calendar.HOUR_OF_DAY)*60+today.get(Calendar.MINUTE), day=today.get(Calendar.DAY_OF_WEEK);
            int yesterday=day==Calendar.SUNDAY?Calendar.SATURDAY:day-1;
            JSONArray tasks=list(context);
            for(int i=0;i<tasks.length();i++) {
                JSONObject task=tasks.getJSONObject(i); if(!task.optBoolean("enabled",true)||!"pause".equals(task.optString("type"))) continue;
                int from=minutes(task.optString("time")),to=minutes(task.optString("end")); JSONArray days=task.optJSONArray("days");
                if(from<0||to<0||from==to) continue;
                if(from<to && includesDay(days,day) && current>=from && current<to) return true;
                if(from>to && ((includesDay(days,day)&&current>=from)||(includesDay(days,yesterday)&&current<to))) return true;
            }
        } catch(Exception ignored) { }
        return false;
    }
    public static synchronized void tick(Context context,GateState state) {
        try {
            long now=System.currentTimeMillis(); SharedPreferences store=prefs(context);
            // 定时时段只影响实际计时，不更改家长手动开关。
            syncQuizWindow(context,state,now);
            long previous=lastEvaluated>0?lastEvaluated:store.getLong("last_eval",now);
            if(previous>now) previous=now;
            // 离线较久时只补最近 24 小时的到点事件，避免积累多日弹题。
            previous=Math.max(previous,now-24L*60*60*1000);
            JSONArray tasks=list(context);
            for(int i=0;i<tasks.length();i++) {
                JSONObject task=tasks.getJSONObject(i); String type=task.optString("type");
                if(!task.optBoolean("enabled",true)||!("quiz".equals(type)||"practice".equals(type))) continue;
                int minute=minutes(task.optString("time")); if(minute<0) continue;
                for(int offset=1;offset>=0;offset--) {
                    Calendar event=Calendar.getInstance(); event.setTimeInMillis(now); event.add(Calendar.DAY_OF_YEAR,-offset);
                    if(!includesDay(task.optJSONArray("days"),event.get(Calendar.DAY_OF_WEEK))) continue;
                    event.set(Calendar.HOUR_OF_DAY,minute/60); event.set(Calendar.MINUTE,minute%60); event.set(Calendar.SECOND,0); event.set(Calendar.MILLISECOND,0);
                    long when=event.getTimeInMillis(); String day=new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(event.getTime());
                    String key="fired_"+task.getString("id");
                    if(when>previous && when<=now && !day.equals(store.getString(key,""))) {
                        if("practice".equals(type)) {
                            // 保留尚未完成的 50 题进度，到点继续练习。
                            try { PracticeBank.get(context).startByParent(); store.edit().putString(key,day).commit(); }
                            catch(Exception e) { state.error("定时刷题启动失败，请检查题库和设备状态"); }
                        } else if(state.lockNow()) store.edit().putString(key,day).commit(); else state.error("定时弹题保存失败");
                    }
                }
            }
            lastEvaluated=now;
            if(now-lastPersisted>=30000) { store.edit().putLong("last_eval",now).apply(); lastPersisted=now; }
        } catch(Exception e) { state.error("定时任务读取失败"); }
    }
    private static void syncQuizWindow(Context context,GateState state,long now) throws Exception {
        Calendar today=Calendar.getInstance(); today.setTimeInMillis(now);
        int current=today.get(Calendar.HOUR_OF_DAY)*60+today.get(Calendar.MINUTE),day=today.get(Calendar.DAY_OF_WEEK);
        int yesterday=day==Calendar.SUNDAY?Calendar.SATURDAY:day-1;
        JSONArray tasks=list(context); String window=""; int questions=1;
        for(int i=0;i<tasks.length();i++) {
            JSONObject task=tasks.getJSONObject(i);
            if(!task.optBoolean("enabled",true) || !"quiz-window".equals(task.optString("type"))) continue;
            int from=minutes(task.optString("time")),to=minutes(task.optString("end")); JSONArray days=task.optJSONArray("days");
            boolean previousDay=from>to && current<to && includesDay(days,yesterday);
            boolean active=from>=0 && to>=0 && from!=to && (from<to
                ? includesDay(days,day) && current>=from && current<to
                : (includesDay(days,day) && current>=from) || previousDay);
            if(!active) continue;
            Calendar start=(Calendar)today.clone(); if(previousDay) start.add(Calendar.DAY_OF_YEAR,-1);
            window=task.getString("id")+"_"+new SimpleDateFormat("yyyyMMdd",Locale.ROOT).format(start.getTime());
            questions=Math.max(1,Math.min(20,task.optInt("questions",1))); break;
        }
        if(!state.syncScheduleWindow(window,questions)) throw new IllegalStateException("定时时段保存失败");
    }
}
