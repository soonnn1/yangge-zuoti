package cn.tan.watchgate;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.wifi.WifiManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 家长网页由手机本机提供；所有管理接口均要求高熵密钥。 */
public final class WebAdmin implements AutoCloseable {
    private final Context context;
    private final GateState state;
    private final ImportReviews importReviews;
    // 网页请求和长时间的 AI 任务分开排队，避免生成任务占满网页线程。
    private final ThreadPoolExecutor requestPool=new ThreadPoolExecutor(6,6,0L,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(64));
    private final ExecutorService jobPool=Executors.newFixedThreadPool(4);
    private final AtomicBoolean generatingQuestions=new AtomicBoolean(false);
    private final AtomicBoolean analyzingExam=new AtomicBoolean(false);
    private final AtomicBoolean importingDocument=new AtomicBoolean(false);
    private volatile String documentStatus="尚未选择文档", documentJob="";
    private volatile JSONArray documentPreview=new JSONArray();
    private volatile String generationStatus="尚未生成题库";
    private volatile long generationStartedElapsed;
    private final android.content.SharedPreferences generationPrefs, operationPrefs;
    private volatile String generationChapter="";
    private volatile String examStatus="尚未分析试卷";
    private volatile boolean alive;
    private ServerSocket server;
    WebAdmin(Context context) {
        this.context=context.getApplicationContext(); state=GateState.get(context); importReviews=new ImportReviews(this.context);
        generationPrefs=this.context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_generation_status",Context.MODE_PRIVATE);
        operationPrefs=this.context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_operations",Context.MODE_PRIVATE);
        documentStatus=operationPrefs.getString("documentStatus","尚未选择文档");
        try { documentPreview=new JSONArray(operationPrefs.getString("documentIssues","[]")); if(documentPreview.length()>0) documentJob=UUID.randomUUID().toString(); } catch(Exception ignored) { documentPreview=new JSONArray(); }
        examStatus=operationPrefs.getString("examStatus","尚未分析试卷");
        if(operationPrefs.getBoolean("documentRunning",false)) documentStatus="上次文档处理中断，请重新上传";
        if(operationPrefs.getBoolean("examRunning",false)) examStatus="上次图片处理中断，请重新上传";
        operationPrefs.edit().putBoolean("documentRunning",false).putBoolean("examRunning",false)
            .putString("documentStatus",documentStatus).putString("examStatus",examStatus).apply();
        generationChapter=generationPrefs.getString("chapter","");
        generationStatus=generationPrefs.getString("status","尚未生成题库");
        if(generationPrefs.getBoolean("running",false)) {
            generationStatus="上次生成中断，可重新点击生成继续";
            generationPrefs.edit().putBoolean("running",false).putString("status",generationStatus).apply();
        }
    }
    public static String address(Context context) {
        try {
            WifiManager wifi=(WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            int ip=wifi.getConnectionInfo().getIpAddress();
            if(ip==0) return "";
            return (ip&255)+"."+((ip>>8)&255)+"."+((ip>>16)&255)+"."+((ip>>24)&255);
        } catch(RuntimeException e) { return ""; }
    }
    public void start() {
        alive=true;
        new Thread(() -> {
            while(alive) {
                try {
                    server=new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(8765));
                    while(alive) {
                        Socket socket=server.accept();
                        if(!(socket.getInetAddress().isSiteLocalAddress() || socket.getInetAddress().isLoopbackAddress())) { socket.close(); continue; }
                        try { requestPool.execute(new ClientTask(socket)); } catch(RejectedExecutionException e) { socket.close(); }
                    }
                } catch(IOException e) {
                    if(alive) { state.error("网页服务暂不可用，正在重试"); try { Thread.sleep(5000); } catch(InterruptedException ignored) { } }
                }
            }
        },"parent-web-listener").start();
    }
    private final class ClientTask implements Runnable {
        private final Socket socket;
        ClientTask(Socket socket) { this.socket=socket; }
        @Override public void run() { handle(socket); }
        void closeSocket() { try { socket.close(); } catch(IOException ignored) { } }
    }
    private void handle(Socket socket) {
        try(Socket client=socket) {
            client.setSoTimeout(5000);
            InputStream in=new BufferedInputStream(client.getInputStream());
            String first=line(in,2048); if(first==null) return;
            String[] request=first.split(" "); if(request.length!=3) { reply(client,400,"text/plain","请求无效"); return; }
            String method=request[0], path=request[1];
            int queryAt=path.indexOf('?'); if(queryAt>=0) path=path.substring(0,queryAt);
            int length=0; String key="", familyKey="", contentType="";
            for(int i=0;i<40;i++) {
                String h=line(in,4096); if(h==null) return; if(h.isEmpty()) break;
                int colon=h.indexOf(':'); if(colon<1) { reply(client,400,"text/plain","请求无效"); return; }
                String name=h.substring(0,colon).trim().toLowerCase(Locale.ROOT), value=h.substring(colon+1).trim();
                if(name.equals("content-length")) length=Integer.parseInt(value);
                else if(name.equals("x-parent-key")) key=value;
                else if(name.equals("x-family-key")) familyKey=value;
                else if(name.equals("content-type")) contentType=value;
            }
            boolean examUpload=method.equals("POST")&&(path.equals("/api/exam/analyze")||path.equals("/api/document/prepare"));
            boolean textbookUpload=method.equals("POST")&&path.equals("/api/textbook/import");
            // 大体积上传先验家长身份，避免未配对设备占用内存。
            if((examUpload||textbookUpload) && (!state.hasPin() || !MessageDigest.isEqual(state.webKey().getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))) {
                reply(client,401,"application/json","{\"error\":\"家长密钥不正确\"}"); return;
            }
            if(length<0 || length>(examUpload?12*1024*1024:textbookUpload?8*1024*1024:65536)) { reply(client,413,"text/plain","请求过大"); return; }
            if(examUpload||textbookUpload) client.setSoTimeout(30000);
            byte[] body=new byte[length]; int pos=0; while(pos<length) { int n=in.read(body,pos,length-pos); if(n<0) return; pos+=n; }
            if(method.equals("GET") && path.equals("/connect")) { asset(client,"connect.html","text/html; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/connect.js")) { asset(client,"connect.js","application/javascript; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/favicon.png")) { asset(client,"favicon.png","image/png"); return; }
            if(method.equals("GET") && path.equals("/parent.apk")) { asset(client,"parent.apk","application/vnd.android.package-archive"); return; }
            if(method.equals("GET") && path.equals("/")) { asset(client,"index.html","text/html; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/family")) { asset(client,"family.html","text/html; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/app.js")) { asset(client,"app.js","application/javascript; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/family.js")) { asset(client,"family.js","application/javascript; charset=utf-8"); return; }
            if(method.equals("GET") && path.equals("/style.css")) { asset(client,"style.css","text/css; charset=utf-8"); return; }
            if(method.equals("POST") && path.equals("/api/pair") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
                String code=input.optString("code", "");
                if(!state.hasPin() || !code.matches("[0-9]{4,8}") || !state.checkPin(code)) {
                    reply(client,401,"application/json","{\"error\":\"家长密码不正确或尝试次数过多，请稍后重试\"}"); return;
                }
                JSONObject result=new JSONObject(); result.put("key",state.webKey());
                reply(client,200,"application/json",result.toString()); return;
            }
            // 家人入口只允许读取简化状态和执行快捷操作。
            if(path.startsWith("/api/family/")) {
                if(!state.hasPin() || !state.checkFamilyShareKey(familyKey)) {
                    reply(client,401,"application/json","{\"error\":\"分享链接无效或已撤销\"}"); return;
                }
                if(method.equals("GET") && path.equals("/api/family/state")) {
                    reply(client,200,"application/json",familySnapshot().toString()); return;
                }
                if(method.equals("POST") && path.equals("/api/family/action") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                    JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8)); String action=input.optString("action"); boolean ok;
                    if(action.equals("lock")) ok=state.lockNow();
                    else if(action.equals("release")) {
                        synchronized(state) {
                            if(state.familyReleaseRemaining()<=0) {
                                reply(client,429,"application/json","{\"error\":\"今天的家长放行次数已用完\"}"); return;
                            }
                            ok=state.unlock();
                            if(ok&&!state.recordFamilyRelease()) {
                                reply(client,400,"application/json","{\"error\":\"放行次数保存失败，请重试\"}"); return;
                            }
                        }
                        if(ok) { QuestionBank.get(context).clearSession(); GuardService.releaseBlacklistCurrent(); }
                    } else if(action.equals("practice")) {
                        try {
                            PracticeBank practice=PracticeBank.get(context); JSONObject current=practice.parentState();
                            if(current.optBoolean("enabled")&&!current.optBoolean("complete")) practice.stopByParent();
                            else practice.startByParent();
                        } catch(Exception e) {
                            reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"刷题模式操作失败":e.getMessage()).toString()); return;
                        }
                        GuardService.refresh(); reply(client,200,"application/json",familySnapshot().toString()); return;
                    } else { reply(client,400,"application/json","{\"error\":\"未知操作\"}"); return; }
                    if(!ok) { reply(client,400,"application/json","{\"error\":\"操作失败，请稍后重试\"}"); return; }
                    GuardService.refresh(); reply(client,200,"application/json",familySnapshot().toString()); return;
                }
                reply(client,404,"text/plain","未找到"); return;
            }
            if(!state.hasPin() || !MessageDigest.isEqual(state.webKey().getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8))) {
                reply(client,401,"application/json", "{\"error\":\"家长密钥不正确\"}"); return;
            }
            if(method.equals("GET") && path.equals("/api/family-link")) {
                String token=state.familyShareKey(); JSONObject result=new JSONObject().put("active",!token.isEmpty());
                if(!token.isEmpty()) result.put("token",token);
                reply(client,200,"application/json",result.toString()); return;
            }
            if(method.equals("POST") && path.equals("/api/family-link")) {
                String token=state.createFamilyShareKey();
                if(token.isEmpty()) { reply(client,400,"application/json","{\"error\":\"分享链接生成失败\"}"); return; }
                reply(client,200,"application/json",new JSONObject().put("active",true).put("token",token).toString()); return;
            }
            if(method.equals("POST") && path.equals("/api/family-link/revoke")) {
                if(!state.revokeFamilyShareKey()) { reply(client,400,"application/json","{\"error\":\"分享链接撤销失败\"}"); return; }
                reply(client,200,"application/json","{\"active\":false}"); return;
            }
            if(method.equals("GET") && path.equals("/api/schedules")) { reply(client,200,"application/json",new JSONObject().put("tasks",ScheduleManager.list(context)).toString()); return; }
            if(method.equals("POST") && path.equals("/api/schedules")) {
                try {
                    JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8)); ScheduleManager.save(context,input.getJSONArray("tasks"));
                    GuardService.refresh(); reply(client,200,"application/json",new JSONObject().put("tasks",ScheduleManager.list(context)).toString());
                } catch(Exception e) { JSONObject error=new JSONObject().put("error",e.getMessage()==null?"定时任务保存失败":e.getMessage()); reply(client,400,"application/json",error.toString()); }
                return;
            }
            if(method.equals("GET") && path.equals("/api/state")) { reply(client,200,"application/json",snapshot().toString()); return; }
            if(method.equals("POST") && path.equals("/api/launcher-icon") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                try {
                    JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
                    Object value=input.get("visible");
                    if(!(value instanceof Boolean)) throw new IllegalArgumentException("图标状态无效");
                    // 仅切换本应用的桌面入口，保留主界面及后台服务。
                    context.getPackageManager().setComponentEnabledSetting(launcherEntry(),
                        (Boolean)value?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP);
                    reply(client,200,"application/json",snapshot().toString());
                } catch(Exception e) { reply(client,400,"application/json","{\"error\":\"无法更改桌面图标状态\"}"); }
                return;
            }
            if(method.equals("POST") && path.equals("/api/practice/action")) {
                try {
                    JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));String action=input.optString("action");
                    if(action.equals("start")) {
                        if(!state.hasPin())throw new IllegalArgumentException("请先在儿童端设置家长密码");
                        PracticeBank.get(context).startByParent();
                    } else if(action.equals("stop"))PracticeBank.get(context).stopByParent();
                    else throw new IllegalArgumentException("未知刷题操作");
                    GuardService.refresh();reply(client,200,"application/json",snapshot().toString());
                } catch(Exception e){reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"刷题操作失败":e.getMessage()).toString());}
                return;
            }
            if(method.equals("GET") && path.equals("/api/usage")) { reply(client,200,"application/json",usage().toString()); return; }
            if(method.equals("GET") && path.equals("/api/apps")) { reply(client,200,"application/json",appRules().toString()); return; }
            if(method.equals("GET") && path.equals("/api/quizbank")) { reply(client,200,"application/json",quizBankState().toString()); return; }
            if(method.equals("GET") && path.equals("/api/import-reviews")) { reply(client,200,"application/json",importReviews.pending().toString()); return; }
            if(method.equals("POST") && path.equals("/api/import-reviews/ai")) {
                try { reply(client,202,"application/json",importReviews.startAi(new JSONObject(new String(body,StandardCharsets.UTF_8)),jobPool).toString()); }
                catch(Exception e) { reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"AI补全未开始":e.getMessage()).toString()); }
                return;
            }
            if(method.equals("POST") && path.equals("/api/import-reviews/resolve")) {
                if(analyzingExam.get()||importingDocument.get()) { reply(client,409,"application/json","{\"error\":\"请等图片或文档处理完成后再确认\"}"); return; }
                try {
                    JSONObject result=importReviews.resolve(new JSONObject(new String(body,StandardCharsets.UTF_8)));
                    reply(client,200,"application/json",result.toString());
                } catch(Exception e) { reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"处理失败，请重试":e.getMessage()).toString()); }
                return;
            }
            if(method.equals("GET") && path.equals("/api/document")) { reply(client,200,"application/json",documentState().toString()); return; }
            if(method.equals("POST") && path.equals("/api/document/prepare")) { prepareDocument(client,contentType,body); return; }
            if(method.equals("GET") && path.equals("/api/curriculum")) { reply(client,200,"application/json",curriculum().toString()); return; }
            if(method.equals("POST") && path.equals("/api/textbook/import") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                try { int count=TextbookKnowledge.saveImported(context,new JSONObject(new String(body,StandardCharsets.UTF_8)));
                    reply(client,200,"application/json",new JSONObject().put("count",count).toString()); }
                catch(Exception e){reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"教材导入失败":e.getMessage()).toString());}return;
            }
            if(method.equals("GET") && path.equals("/api/exam")) { reply(client,200,"application/json",examState().toString()); return; }
            if(method.equals("POST") && path.equals("/api/exam/analyze")) { startExam(client,contentType,body,false); return; }
            if(method.equals("POST") && path.equals("/api/exam/retry")) { startExam(client,contentType,body,true); return; }
            if(method.equals("POST") && path.equals("/api/question-list") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
                int grade=input.optInt("grade",0); String chapterId=input.optString("chapterId",""),source=input.optString("sourceType","");
                boolean allSource="__all__".equals(chapterId)&&("image".equals(source)||"document".equals(source));
                JSONObject chapter=allSource?null:curriculumChapter(grade,input.optString("term",""),chapterId);
                if(!allSource&&chapter==null) { reply(client,400,"application/json","{\"error\":\"请选择有效教材章节\"}"); return; }
                QuestionBank bank=QuestionBank.get(context); JSONObject result=bank.questionTable(grade,chapterId,source);
                if(chapter!=null) result.put("uncovered",bank.uncoveredConcepts(chapterId,chapter.getJSONArray("concepts")));
                reply(client,200,"application/json",result.toString()); return;
            }
            if(method.equals("POST") && path.equals("/api/question-bank/apply")) {
                JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
                int grade=input.optInt("grade",0);String chapterId=input.optString("chapterId",""),source=input.optString("sourceType","");
                boolean allSource="__all__".equals(chapterId)&&("image".equals(source)||"document".equals(source));
                if(!allSource&&curriculumChapter(grade,input.optString("term",""),chapterId)==null){reply(client,400,"application/json","{\"error\":\"请选择有效教材章节\"}");return;}
                try{int count=QuestionBank.get(context).applyQuestionBank(source,input.optJSONArray("questionIds"),grade,chapterId);
                    reply(client,200,"application/json",new JSONObject().put("count",count).toString());}
                catch(Exception e){reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"应用题库失败":e.getMessage()).toString());}return;
            }
            if(method.equals("POST") && path.equals("/api/question-bank/delete") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
                int grade=input.optInt("grade",0); String chapterId=input.optString("chapterId",""),source=input.optString("sourceType","");
                boolean allSource="__all__".equals(chapterId)&&("image".equals(source)||"document".equals(source));
                if(!allSource&&curriculumChapter(grade,input.optString("term",""),chapterId)==null) { reply(client,400,"application/json","{\"error\":\"请选择有效教材章节\"}"); return; }
                if(generatingQuestions.get()||analyzingExam.get()||importingDocument.get()) { reply(client,409,"application/json","{\"error\":\"题库正在生成或导入，请完成后再删除\"}"); return; }
                try { JSONObject result=PracticeBank.get(context).deleteQuestionBank(source,input.optJSONArray("rootIds"),grade,chapterId);
                    reply(client,200,"application/json",result.toString()); }
                catch(Exception e) { reply(client,400,"application/json",new JSONObject().put("error",e.getMessage()==null?"删除题库失败":e.getMessage()).toString()); }
                return;
            }
            if(method.equals("POST") && path.equals("/api/generate")) { startQuestionGeneration(client,contentType,body); return; }
            if(method.equals("POST") && path.equals("/api/app-rules")) { saveAppRules(client,contentType,body); return; }
            if(method.equals("POST") && path.equals("/api/action") && contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
                JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8)); String action=input.optString("action"); boolean ok;
                switch(action) {
                    case "start": ok=state.enable(true); break;
                    case "pause": ok=state.enable(false); break;
                    case "demo": ok=state.startDemo() && state.enable(true); break;
                    case "lock": ok=state.lockNow(); break;
                    case "release":
                        // 先停止刷题并保留进度，再执行原有的家长放行。
                        try { if(PracticeBank.get(context).enabled()) PracticeBank.get(context).stopByParent(); }
                        catch(Exception e) { reply(client,400,"application/json","{\"error\":\"结束刷题失败，请重试\"}"); return; }
                        ok=state.unlock(); if(ok) { QuestionBank.get(context).clearSession(); GuardService.releaseBlacklistCurrent(); } break;
                    case "limit": int minutes=input.optInt("minutes",0); ok=minutes>=1 && minutes<=120 && state.setLimit(minutes*60_000L); break;
                    case "question-count": ok=state.setRoundQuestions(input.optInt("count",0)); break;
                    case "level": int level=input.optInt("level",0); ok=level>=1 && level<=3 && state.setLevel(level); break;
                    default: reply(client,400,"application/json","{\"error\":\"未知操作\"}"); return;
                }
                if(!ok) { reply(client,400,"application/json","{\"error\":\"操作失败，请检查输入或手机存储\"}"); return; }
                GuardService.refresh(); reply(client,200,"application/json",snapshot().toString()); return;
            }
            reply(client,404,"text/plain","未找到");
        } catch(Exception e) {
            // 只记录异常类型，不记录家长密钥、题目或请求内容。
            if(!(e instanceof SocketException)) android.util.Log.w("WatchGateWeb","网页请求处理失败："+e.getClass().getSimpleName());
        }
    }

    private JSONObject quizBankState() throws Exception {
        QuestionBank bank=QuestionBank.get(context); JSONObject result=new JSONObject();
        result.put("configured",ApiKeyVault.isConfigured(context)); result.put("generating",generatingQuestions.get());
        result.put("autoSupplyRunning",bank.autoSupplyRunning()); result.put("autoSupplyStatus",bank.autoSupplyStatus());
        result.put("autoSupplyRevision",bank.autoSupplyRevision());
        result.put("status",generationStatus); result.put("questions",bank.count()); result.put("wrongCount",bank.wrongCount());
        result.put("elapsedMs",generatingQuestions.get()&&generationStartedElapsed>0?Math.max(0,android.os.SystemClock.elapsedRealtime()-generationStartedElapsed):0);
        result.put("lastJob",operationPrefs.getString("lastJob",""));
        result.put("progressCurrent",generationChapter.isEmpty()?0:bank.baseCount(generationChapter));
        result.put("progressTarget",generationChapter.isEmpty()?0:QuestionBank.BASE_TARGET);
        return result;
    }

    private interface ImportProgress { void update(String text); }
    private static final class ImportCandidate {
        final String id,stem; final int number; final Question question;
        ImportCandidate(String id,String stem,int number,Question question) { this.id=id; this.stem=stem; this.number=number; this.question=question; }
    }
    private static final class ImportOutcome {
        int added,duplicates; final JSONArray issues=new JSONArray();
    }
    private static void addImportIssue(JSONArray issues,int number,String stem,String reason) throws Exception {
        issues.put(new JSONObject().put("number",number).put("stem",shortText(stem,500)).put("reason",shortText(reason,300)));
    }
    /** 只导入来源清晰且独立复核答案一致的题，其余保留原因供家长查看。 */
    private ImportOutcome importRecognized(String apiKey,JSONArray source,int grade,String chapterId,String chapterTitle,String term,
            boolean nested,ImportProgress progress) throws Exception {
        ImportOutcome outcome=new ImportOutcome(); List<ImportCandidate> candidates=new ArrayList<>();
        int limit=Math.min(source.length(),100);
        for(int i=0;i<limit;i++) {
            JSONObject row=source.optJSONObject(i); if(row==null) { addImportIssue(outcome.issues,i+1,"","未识别到题目内容"); continue; }
            JSONObject item=nested?row.optJSONObject("importItem"):row;
            String stem=item==null?row.optString("stem",""):item.optString("stem",row.optString("stem",""));
            if(item==null || !item.optBoolean("sourceClear",false) || !"high".equalsIgnoreCase(item.optString("answerConfidence",""))) {
                String reason=item==null?"题干或答案无法确定":item.optString("issueReason","");
                addImportIssue(outcome.issues,i+1,stem,reason.isEmpty()?"题干不清或答案不确定":reason); continue;
            }
            try {
                JSONObject raw=new JSONObject(item.toString()); String id=UUID.randomUUID().toString();
                String subject=raw.optString("subject",row.optString("subject","数学")).trim(); if(subject.isEmpty()||subject.length()>30) subject="数学";
                raw.put("id",id).put("rootId",id).put("grade",grade).put("subject",subject).put("aiGenerated",true);
                String kind="__exam__".equals(chapterId)?"exam":"base";
                Question q=Question.fromStored(raw).inChapter(chapterId,chapterTitle,term,kind).fromSource(nested?"image":"document");
                candidates.add(new ImportCandidate(id,stem,i+1,q));
            } catch(Exception invalid) { addImportIssue(outcome.issues,i+1,stem,"题干、四个选项、答案或提示不完整"); }
        }
        if(source.length()>limit) addImportIssue(outcome.issues,limit+1,"","题目超过单次上限，请拆分文件后继续导入");
        List<Question> accepted=new ArrayList<>();
        for(int start=0;start<candidates.size();start+=10) {
            int end=Math.min(start+10,candidates.size()); progress.update("正在核对题目 "+start+"/"+candidates.size());
            JSONArray batch=new JSONArray();
            for(int i=start;i<end;i++) {
                ImportCandidate c=candidates.get(i); JSONArray options=new JSONArray(); for(String choice:c.question.choices) options.put(choice);
                batch.put(new JSONObject().put("id",c.id).put("subject",c.question.subject).put("stem",c.question.title).put("options",options));
            }
            try {
                JSONArray results=QwenClient.verifyImportedQuestions(apiKey,batch); Map<String,JSONObject> byId=new HashMap<>(); Set<String> duplicates=new HashSet<>();
                for(int i=0;i<results.length();i++) { JSONObject row=results.optJSONObject(i); if(row==null) continue; String id=row.optString("id",""); if(byId.put(id,row)!=null) duplicates.add(id); }
                for(int i=start;i<end;i++) {
                    ImportCandidate c=candidates.get(i); JSONObject result=byId.get(c.id);
                    if(result!=null && !duplicates.contains(c.id) && result.optBoolean("valid",false) && result.optInt("answerIndex",-1)==c.question.answerIndex) accepted.add(c.question);
                    else { String reason=result==null?"独立核对未返回结果":result.optString("reason","");
                        addImportIssue(outcome.issues,c.number,c.stem,reason.isEmpty()?"独立核对不通过或答案不一致":reason); }
                }
            } catch(Exception e) {
                for(int i=start;i<end;i++) { ImportCandidate c=candidates.get(i); addImportIssue(outcome.issues,c.number,c.stem,"独立核对失败，请稍后重新导入"); }
            }
            progress.update("正在核对题目 "+end+"/"+candidates.size());
        }
        if(!accepted.isEmpty()) {
            progress.update("正在保存通过核对的题目");
            QuestionBank bank=QuestionBank.get(context); outcome.added=bank.installRecognized(accepted);
            outcome.duplicates=accepted.size()-outcome.added;
            if(outcome.added>0 && !"__exam__".equals(chapterId)) bank.setActiveChapter(grade,chapterId);
        }
        // 保留识别草稿及导入归属，家长可在弹窗中补全或修改后确认。
        for(int i=0;i<outcome.issues.length();i++) {
            JSONObject issue=outcome.issues.getJSONObject(i),draft=new JSONObject(); int index=issue.optInt("number",1)-1;
            JSONObject row=index>=0&&index<source.length()?source.optJSONObject(index):null;
            JSONObject original=row==null?null:(nested?row.optJSONObject("importItem"):row);
            if(original!=null) draft=new JSONObject(original.toString());
            draft.put("stem",draft.optString("stem",issue.optString("stem"))).put("grade",grade);
            if(draft.optString("subject").isEmpty()) draft.put("subject",row==null?"":row.optString("subject"));
            issue.put("draft",draft).put("sourceType",nested?"image":"document").put("chapterId",chapterId).put("chapterTitle",chapterTitle).put("term",term);
        }
        importReviews.enqueue(outcome.issues);
        return outcome;
    }

    private JSONObject documentState() throws Exception {
        if(!importingDocument.get()) documentStatus=operationPrefs.getString("documentStatus",documentStatus);
        return new JSONObject().put("running",importingDocument.get()).put("status",documentStatus)
            .put("job",documentJob).put("questions",documentPreview);
    }
    private void prepareDocument(Socket client,String contentType,byte[] body) throws Exception {
        if(!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) { reply(client,415,"application/json","{\"error\":\"请求格式无效\"}"); return; }
        if(!ApiKeyVault.isConfigured(context)) { reply(client,400,"application/json","{\"error\":\"请先设置百炼 API Key\"}"); return; }
        JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
        String filename=input.optString("filename","").toLowerCase(Locale.ROOT),encoded=input.optString("content","");
        int grade=input.optInt("grade",0); String term=input.optString("term",""),chapterId=input.optString("chapterId","");
        JSONObject chapter=curriculumChapter(grade,term,chapterId);
        if(chapter==null || !(filename.endsWith(".doc")||filename.endsWith(".docx")) || encoded.length()>6*1024*1024) {
            reply(client,400,"application/json","{\"error\":\"请选择章节和不超过 4 MB 的 doc 或 docx 文件\"}"); return;
        }
        byte[] file;
        try { file=android.util.Base64.decode(encoded,android.util.Base64.DEFAULT); }
        catch(IllegalArgumentException e) { reply(client,400,"application/json","{\"error\":\"文件无法读取\"}"); return; }
        if(file.length==0||file.length>4*1024*1024) { reply(client,400,"application/json","{\"error\":\"文件为空或超过 4 MB\"}"); return; }
        if(!importingDocument.compareAndSet(false,true)) { reply(client,409,"application/json","{\"error\":\"文档正在解析\"}"); return; }
        documentStatus="正在上传并解析文档"; documentJob=""; documentPreview=new JSONArray();
        operationPrefs.edit().putString("lastJob","document").putBoolean("documentRunning",true).putString("documentStatus",documentStatus).putString("documentIssues","[]").commit();
        try {
            jobPool.execute(() -> {
                try {
                    JSONObject result=DocumentImporter.parse(ApiKeyVault.read(context),filename,file);
                    JSONArray source=result.optJSONArray("questions");
                    if(source==null || source.length()==0) throw new IllegalStateException("未识别到题目，请检查文档或拆分后重试");
                    ImportOutcome outcome=importRecognized(ApiKeyVault.read(context),source,grade,chapterId,chapter.optString("title"),term,false,text -> documentStatus=text);
                    documentPreview=outcome.issues; documentJob=outcome.issues.length()>0?UUID.randomUUID().toString():"";
                    documentStatus="文档处理完成：已导入 "+outcome.added+" 道，重复 "+outcome.duplicates+" 道，需处理 "+outcome.issues.length()+" 道";
                } catch(Exception e) {
                    String message=e.getMessage(); documentStatus="文档处理失败："+(message==null?"请检查网络、API Key 和模型权限":message.substring(0,Math.min(160,message.length())));
                } finally {
                    importingDocument.set(false);
                    operationPrefs.edit().putBoolean("documentRunning",false).putString("documentStatus",documentStatus)
                        .putString("documentIssues",documentPreview.toString()).commit();
                }
            });
        } catch(RejectedExecutionException e) { importingDocument.set(false); documentStatus="文档处理失败：服务繁忙";
            operationPrefs.edit().putBoolean("documentRunning",false).putString("documentStatus",documentStatus).commit();
            reply(client,503,"application/json","{\"error\":\"服务繁忙，请重试\"}"); return; }
        reply(client,202,"application/json",documentState().toString());
    }
    private android.content.SharedPreferences examPrefs() {
        return context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_exam_v1",Context.MODE_PRIVATE);
    }

    private JSONObject examState() throws Exception {
        if(!analyzingExam.get()) examStatus=operationPrefs.getString("examStatus",examStatus);
        String saved=examPrefs().getString("report","");
        String status=!saved.isEmpty()&&examStatus.equals("尚未分析试卷")?"已保存上次分析":examStatus;
        return new JSONObject().put("running",analyzingExam.get()).put("status",status)
            .put("report",saved.isEmpty()?JSONObject.NULL:new JSONObject(saved));
    }

    private void saveExamReport(JSONObject report) throws Exception {
        if(!examPrefs().edit().putString("report",report.toString()).commit()) throw new IllegalStateException("无法保存试卷分析");
    }

    private void startExam(Socket client,String contentType,byte[] body,boolean retry) throws Exception {
        if(!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) { reply(client,415,"application/json","{\"error\":\"请求格式无效\"}"); return; }
        if(!ApiKeyVault.isConfigured(context)) { reply(client,400,"application/json","{\"error\":\"请先在儿童手机设置百炼 API Key\"}"); return; }
        JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
        int grade=input.optInt("grade",0); JSONArray images=input.optJSONArray("images");
        String requestedChapter=input.optString("chapterId",""), requestedTerm=input.optString("term","");
        JSONObject selectedChapter=requestedChapter.isEmpty()?null:curriculumChapter(grade,requestedTerm,requestedChapter);
        if(!retry && selectedChapter==null) { reply(client,400,"application/json","{\"error\":\"请先选择有效章节，再导入图片\"}"); return; }
        if(!retry) {
            if(grade<1||grade>12||images==null||images.length()<1||images.length()>4) { reply(client,400,"application/json","{\"error\":\"请选择年级和 1 至 4 张试卷图片\"}"); return; }
            for(int i=0;i<images.length();i++) {
                String image=images.optString(i,"");
                if(!image.startsWith("data:image/jpeg;base64,") || image.length()<100) { reply(client,400,"application/json","{\"error\":\"图片格式无效，请重新选择\"}"); return; }
            }
        } else if(examPrefs().getString("report","").isEmpty()) { reply(client,400,"application/json","{\"error\":\"没有可继续的试卷分析\"}"); return; }
        if(!analyzingExam.compareAndSet(false,true)) { reply(client,409,"application/json","{\"error\":\"试卷正在处理，请稍候\"}"); return; }
        examStatus=retry?"正在继续生成专项练习":"正在分析图片，请稍候";
        operationPrefs.edit().putString("lastJob","exam").putBoolean("examRunning",true).putString("examStatus",examStatus).commit();
        try {
            jobPool.execute(() -> {
                try {
                    String apiKey=ApiKeyVault.read(context);
                    JSONObject report;
                    if(retry) report=new JSONObject(examPrefs().getString("report","{}"));
                    else {
                        JSONObject raw=QwenClient.analyzeExam(apiKey,grade,images);
                        report=normalizeExam(raw,grade);
                        String selectedId=selectedChapter==null?"__exam__":requestedChapter;
                        String selectedTitle=selectedChapter==null?"试卷专项":selectedChapter.optString("title");
                        ImportOutcome outcome=importRecognized(apiKey,raw.getJSONArray("questions"),grade,selectedId,selectedTitle,selectedChapter==null?"":requestedTerm,true,text -> examStatus=text);
                        String kind=raw.optString("sourceKind","uncertain");
                        if(!Arrays.asList("answered_exam","questions","uncertain").contains(kind)) kind="uncertain";
                        report.put("sourceKind",kind).put("importCount",outcome.added).put("importDuplicates",outcome.duplicates).put("importIssues",outcome.issues);
                        saveExamReport(report);
                    }
                    if("questions".equals(report.optString("sourceKind"))) report.put("weaknesses",new JSONArray());
                    JSONArray groups=report.getJSONArray("weaknesses"); int completed=report.optInt("practiceCount",0);
                    for(int i=0;i<groups.length();i++) {
                        JSONObject group=groups.getJSONObject(i); if(group.optBoolean("done")) continue;
                        String subject=group.getString("subject");
                        examStatus="正在生成"+subject+"专项练习（"+(i+1)+"/"+groups.length()+"）";
                        List<Question> questions=QwenClient.generateExamPractice(apiKey,report.getInt("grade"),subject,group.getJSONArray("points").toString());
                        for(Question q:questions) q.inChapter("__exam__","试卷专项","","exam").fromSource("image");
                        int added=QuestionBank.get(context).installExamPractice(questions);
                        if(added==0) throw new IllegalStateException("未生成新的专项题，请重试");
                        completed+=added; group.put("done",true); report.put("practiceCount",completed); saveExamReport(report);
                    }
                    JSONArray issues=report.optJSONArray("importIssues");
                    examStatus="图片处理完成：已导入 "+report.optInt("importCount",0)+" 道，需处理 "+(issues==null?0:issues.length())+" 道"+(completed>0?"，生成专项题 "+completed+" 道":"");
                } catch(Exception e) {
                    String message=e.getMessage(); examStatus="处理未完成："+(message==null?"请检查网络和百炼额度":message.substring(0,Math.min(120,message.length())));
                } finally { analyzingExam.set(false); operationPrefs.edit().putBoolean("examRunning",false).putString("examStatus",examStatus).commit(); }
            });
        } catch(RejectedExecutionException e) { analyzingExam.set(false); examStatus="图片处理失败：服务繁忙";
            operationPrefs.edit().putBoolean("examRunning",false).putString("examStatus",examStatus).commit();
            reply(client,503,"application/json","{\"error\":\"服务繁忙\"}"); return; }
        reply(client,202,"application/json",examState().toString());
    }

    /** 把不清楚的结果留待核对，只用可信错题归纳薄弱点。 */
    private JSONObject normalizeExam(JSONObject raw,int grade) throws Exception {
        JSONArray source=raw.optJSONArray("questions"), rows=new JSONArray(), groups=new JSONArray();
        if(source==null||source.length()==0) throw new IllegalStateException("未识别到题目，请拍清晰后重试");
        Map<String,JSONObject> bySubject=new LinkedHashMap<>();
        for(int i=0;i<Math.min(source.length(),100);i++) {
            JSONObject q=source.optJSONObject(i); if(q==null) continue;
            String status=q.optString("status","uncertain"),confidence=q.optString("confidence","low"),basis=q.optString("basis","unclear");
            if(!Arrays.asList("correct","wrong","unanswered","uncertain").contains(status) || !Arrays.asList("high","medium").contains(confidence)) status="uncertain";
            // 空白题没有批改依据也可保留“未作答”；判对错必须有可信依据和学生答案。
            if(("correct".equals(status)||"wrong".equals(status)) && ("unclear".equals(basis)||q.optString("studentAnswer","").trim().isEmpty())) status="uncertain";
            String subject=shortText(q.optString("subject",""),20),point=shortText(q.optString("knowledgePoint",""),100);
            JSONObject row=new JSONObject().put("number",shortText(q.optString("number",String.valueOf(i+1)),20))
                .put("stem",shortText(q.optString("stem",""),1000)).put("studentAnswer",shortText(q.optString("studentAnswer",""),500))
                .put("correctAnswer",shortText(q.optString("correctAnswer",""),500)).put("status",status).put("subject",subject)
                .put("knowledgePoint",point).put("reason",shortText(q.optString("reason",""),500)).put("confidence",confidence).put("basis",basis);
            rows.put(row);
            if("questions".equals(raw.optString("sourceKind"))||!"wrong".equals(status)||subject.isEmpty()||point.isEmpty()) continue;
            JSONObject group=bySubject.get(subject);
            if(group==null && bySubject.size()<4) { group=new JSONObject().put("subject",subject).put("points",new JSONArray()).put("done",false); bySubject.put(subject,group); groups.put(group); }
            if(group!=null) { JSONArray points=group.getJSONArray("points"); boolean exists=false; for(int j=0;j<points.length();j++) if(point.equals(points.optString(j))) exists=true; if(!exists&&points.length()<12) points.put(point); }
        }
        if(rows.length()==0) throw new IllegalStateException("未识别到题目，请拍清晰后重试");
        return new JSONObject().put("grade",grade).put("createdAt",System.currentTimeMillis()).put("questions",rows)
            .put("summary",shortText(raw.optString("summary",""),1000)).put("weaknesses",groups).put("practiceCount",0);
    }

    private static String shortText(String value,int limit) { value=value.trim(); return value.substring(0,Math.min(limit,value.length())); }

    private JSONObject appRules() throws Exception {
        JSONArray entries=new JSONArray(); Set<String> white=state.whitelist(), black=state.blacklist();
        Set<String> protectedPackages=protectedPackages(); Map<String,String> labels=new HashMap<>();
        PackageManager packages=context.getPackageManager();
        for(String category:new String[]{Intent.CATEGORY_LAUNCHER,Intent.CATEGORY_LEANBACK_LAUNCHER}) {
            Intent query=new Intent(Intent.ACTION_MAIN).addCategory(category);
            for(ResolveInfo info:packages.queryIntentActivities(query,0)) {
                String pkg=info.activityInfo.packageName;
                if(pkg.equals(context.getPackageName()) || protectedPackages.contains(pkg)) continue;
                labels.put(pkg,info.loadLabel(packages).toString());
            }
        }
        List<String> ids=new ArrayList<>(labels.keySet()); ids.sort((a,b)->labels.get(a).compareToIgnoreCase(labels.get(b)));
        for(String pkg:ids) {
            String policy=white.contains(pkg)?"allow":black.contains(pkg)?"block":"none";
            entries.put(new JSONObject().put("package",pkg).put("label",labels.get(pkg)).put("policy",policy));
        }
        return new JSONObject().put("apps",entries).put("whiteCount",white.size()).put("blackCount",black.size());
    }

    private Set<String> protectedPackages() {
        Set<String> values=new HashSet<>(Arrays.asList("cn.tan.watchgate","com.android.systemui","com.android.phone",
            "com.android.incallui","com.android.emergency","com.huawei.emergency","com.android.settings",
            "com.huawei.systemmanager","com.huawei.permissionmanager"));
        try {
            android.telecom.TelecomManager telecom=(android.telecom.TelecomManager)context.getSystemService(Context.TELECOM_SERVICE);
            String dialer=telecom==null?null:telecom.getDefaultDialerPackage(); if(dialer!=null) values.add(dialer);
        } catch(RuntimeException ignored) { }
        return values;
    }

    private void saveAppRules(Socket client,String contentType,byte[] body) throws Exception {
        if(!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            reply(client,415,"application/json","{\"error\":\"请求格式无效\"}"); return;
        }
        JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8)); JSONArray rows=input.getJSONArray("apps");
        Set<String> available=new HashSet<>(); JSONObject catalog=appRules(); JSONArray catalogRows=catalog.getJSONArray("apps");
        for(int i=0;i<catalogRows.length();i++) available.add(catalogRows.getJSONObject(i).getString("package"));
        Set<String> white=new HashSet<>(), black=new HashSet<>(), seen=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.getJSONObject(i); String pkg=row.getString("package"), policy=row.optString("policy","none");
            if(!available.contains(pkg) || !seen.add(pkg)) { reply(client,400,"application/json","{\"error\":\"应用列表已变化，请刷新后重试\"}"); return; }
            if(policy.equals("allow")) white.add(pkg); else if(policy.equals("block")) black.add(pkg);
            else if(!policy.equals("none")) { reply(client,400,"application/json","{\"error\":\"应用规则无效\"}"); return; }
        }
        if(!state.setAppRules(white,black)) { reply(client,400,"application/json","{\"error\":\"应用规则保存失败\"}"); return; }
        GuardService.refresh(); reply(client,200,"application/json",appRules().toString());
    }

    private JSONArray curriculum() throws Exception {
        return TextbookKnowledge.books(context);
    }

    private JSONObject curriculumChapter(int grade,String term,String chapterId) throws Exception {
        JSONObject loaded=TextbookKnowledge.chapter(context,chapterId);
        if(loaded!=null&&loaded.optInt("grade")==grade&&term.equals(loaded.optString("term")))return loaded;
        JSONArray books=curriculum();
        for(int i=0;i<books.length();i++) {
            JSONObject book=books.getJSONObject(i);
            if(book.optInt("grade")!=grade || !term.equals(book.optString("term"))) continue;
            JSONArray chapters=book.getJSONArray("chapters");
            for(int j=0;j<chapters.length();j++) {
                JSONObject chapter=chapters.getJSONObject(j);
                if(chapterId.equals(chapter.optString("id"))) return chapter;
            }
        }
        return null;
    }

    private void startQuestionGeneration(Socket client,String contentType,byte[] body) throws Exception {
        if(!contentType.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            reply(client,415,"application/json","{\"error\":\"请求格式无效\"}"); return;
        }
        JSONObject input=new JSONObject(new String(body,StandardCharsets.UTF_8));
        int grade=input.optInt("grade",6);
        if(grade<1 || grade>12) {
            reply(client,400,"application/json","{\"error\":\"年级无效\"}"); return;
        }
        String term=input.optString("term",""); String chapterId=input.optString("chapterId","");
        JSONObject chapter=curriculumChapter(grade,term,chapterId);
        if(chapter==null) { reply(client,400,"application/json","{\"error\":\"请选择有效教材章节\"}"); return; }
        String difficulty=input.optString("difficulty","standard");
        if(!Arrays.asList("basic","standard","advanced").contains(difficulty)) {
            reply(client,400,"application/json","{\"error\":\"题目难度无效\"}"); return;
        }
        String guidance=input.optString("guidance","").trim();
        if(guidance.length()>600) {
            reply(client,400,"application/json","{\"error\":\"出题说明不能超过 600 个字符\"}"); return;
        }
        if(!ApiKeyVault.isConfigured(context)) {
            reply(client,400,"application/json","{\"error\":\"请先在儿童手机的家长设置中保存百炼 API Key\"}"); return;
        }
        if(!generatingQuestions.compareAndSet(false,true)) {
            reply(client,409,"application/json","{\"error\":\"题库正在生成，请稍候\"}"); return;
        }
        QuestionBank bank=QuestionBank.get(context);
        bank.setActiveChapter(grade,chapterId);
        bank.setChapterPreferences(chapterId,difficulty,guidance);
        String gradeLabel=QwenClient.gradeName(grade);
        String difficultyLabel=difficulty.equals("basic")?"基础":difficulty.equals("advanced")?"提高":"适中";
        generationStatus="已开始生成"+gradeLabel+term+"册 · "+chapter.optString("title")+"（"+difficultyLabel+"）";
        generationStartedElapsed=android.os.SystemClock.elapsedRealtime();
        generationChapter=chapterId;
        operationPrefs.edit().putString("lastJob","generate").apply();
        generationPrefs.edit().putString("chapter",chapterId).putString("status",generationStatus).putBoolean("running",true).commit();
        try {
            jobPool.execute(() -> {
                try {
                    String apiKey=ApiKeyVault.read(context);
                    int attempts=0;
                    int targetCount=QuestionBank.BASE_TARGET;
                    while(bank.baseCount(chapterId)<targetCount && attempts++<8) {
                        int before=bank.baseCount(chapterId), needed=Math.min(3,targetCount-before);
                        JSONArray missing=bank.uncoveredConcepts(chapterId,chapter.getJSONArray("concepts")),required=new JSONArray();
                        int batchesLeft=(targetCount-before+2)/3, target=Math.min(needed,(missing.length()+batchesLeft-1)/batchesLeft);
                        for(int i=0;i<target;i++) required.put(missing.getString(i));
                        String extra="本批题目必须覆盖这些知识点（同一题可以考查多个）："+required+
                            "。每个知识点须在对应题目的 knowledgePoints 数组中原样标出。不要重复已有题干："+bank.chapterStems(chapterId);
                        generationStatus="正在生成基础题 "+before+"/"+targetCount;
                        try {
                            QwenClient.generateChapter(apiKey,grade,term,chapter,"basic",guidance,needed,"base",extra,new QwenClient.Progress() {
                                @Override public void update(String message) { generationStatus=message; }
                                @Override public void subjectReady(String subject,List<Question> questions) throws Exception {
                                    List<Question> accepted=new ArrayList<>(questions);
                                    if(chapter.has("textbookMaterial")) {
                                        generationStatus="正在核对本批 "+questions.size()+" 道题的答案…";
                                        JSONArray checkInput=new JSONArray();for(Question q:questions)checkInput.put(new JSONObject().put("id",q.id).put("subject",q.subject).put("stem",q.title).put("options",q.toJson().getJSONArray("options")));
                                        JSONArray checks=QwenClient.verifyImportedQuestions(apiKey,checkInput);Map<String,JSONObject> byId=new HashMap<>();Set<String> duplicates=new HashSet<>();
                                        for(int i=0;i<checks.length();i++){JSONObject item=checks.optJSONObject(i);if(item!=null&&byId.put(item.optString("id"),item)!=null)duplicates.add(item.optString("id"));}
                                        // 仅保存通过独立核对的题目，其余题目留待下一批补充。
                                        accepted.clear();
                                        for(Question q:questions){JSONObject item=byId.get(q.id);if(item!=null&&!duplicates.contains(q.id)&&item.optBoolean("valid")&&item.optInt("answerIndex",-1)==q.answerIndex)accepted.add(q);}
                                    }
                                    if(accepted.isEmpty()) throw new IllegalArgumentException("本批没有通过核对的题目");
                                    for(Question q:accepted) q.inChapter(chapterId,chapter.optString("title"),term,"base");
                                    bank.install(accepted);
                                    generationStatus="已保存基础题 "+bank.baseCount(chapterId)+"/"+targetCount;
                                }
                            });
                        } catch(IllegalArgumentException retry) { generationStatus="本批没有可保存的题目，正在补充"; }
                    }
                    int saved=bank.baseCount(chapterId);
                    if(saved<targetCount) throw new IllegalStateException("目前保存 "+saved+"/"+targetCount+" 道，请再次点击继续生成");
                    JSONArray missing=bank.uncoveredConcepts(chapterId,chapter.getJSONArray("concepts"));
                    generationStatus=missing.length()==0?"基础题已生成 "+targetCount+" 道":"已生成 "+targetCount+" 道；AI 标记中还有 "+missing.length()+" 个知识点待核对";
                } catch(Exception e) {
                    String message=e.getMessage();
                    generationStatus="生成失败："+(message==null?"请检查网络、API Key 和百炼额度":message.substring(0,Math.min(150,message.length())));
                } finally {
                    generatingQuestions.set(false);
                    generationPrefs.edit().putBoolean("running",false).putString("status",generationStatus).commit();
                }
            });
        } catch(RejectedExecutionException e) {
            generatingQuestions.set(false); generationStatus="生成失败：服务繁忙";
            generationPrefs.edit().putBoolean("running",false).putString("status",generationStatus).commit();
            reply(client,503,"application/json","{\"error\":\"服务繁忙，请稍后再试\"}"); return;
        }
        reply(client,202,"application/json",quizBankState().toString());
    }
    private JSONObject snapshot() throws Exception {
        JSONObject result=new JSONObject();
        int launcherState=context.getPackageManager().getComponentEnabledSetting(launcherEntry());
        // 桌面入口在清单中默认关闭，只有显式启用时才显示图标。
        result.put("launcherIconVisible",launcherState==PackageManager.COMPONENT_ENABLED_STATE_ENABLED);
        result.put("enabled",state.enabled()); result.put("scheduledPause",ScheduleManager.isPaused(context,System.currentTimeMillis())); result.put("locked",state.locked()); result.put("demo",state.demo()); result.put("quizTest",state.quizTest());
        result.put("scheduledWindow",state.scheduledActive()); result.put("scheduledHold",state.scheduledHolding());
        result.put("practice",PracticeBank.get(context).parentState());
        result.put("usedMs",state.used()); result.put("limitMs",state.limit()); result.put("remainingMs",Math.max(0,state.limit()-state.used()));
        result.put("level",state.level()); result.put("usagePermission",MainActivity.hasUsageAccess(context));
        result.put("roundQuestions",state.roundQuestions());
        result.put("overlayPermission",android.provider.Settings.canDrawOverlays(context));
        result.put("heartbeat",state.heartbeat()); result.put("lastBackground",state.backgroundSummary()); result.put("error",state.error());
        return result;
    }
    private android.content.ComponentName launcherEntry() {
        return new android.content.ComponentName(context,context.getPackageName()+".LauncherEntry");
    }
    private JSONObject familySnapshot() throws Exception {
        boolean enabled=state.enabled(), locked=state.locked();
        String status=!enabled?"管控已暂停":locked?"需要完成答题":"管控中";
        JSONObject practice=PracticeBank.get(context).parentState();
        return new JSONObject().put("status",status).put("enabled",enabled).put("locked",locked)
            .put("remainingMs",Math.max(0,state.limit()-state.used())).put("releaseRemaining",state.familyReleaseRemaining())
            .put("practiceEnabled",practice.optBoolean("enabled")).put("practiceAnswered",practice.optInt("answered"))
            .put("practiceComplete",practice.optBoolean("complete"));
    }
    private JSONObject usage() throws Exception {
        JSONObject result=new JSONObject(); JSONArray entries=new JSONArray();
        if(MainActivity.hasUsageAccess(context)) {
            Set<String> launchable=new HashSet<>();
            for(String category:new String[]{Intent.CATEGORY_LAUNCHER,Intent.CATEGORY_LEANBACK_LAUNCHER}) {
                Intent intent=new Intent(Intent.ACTION_MAIN).addCategory(category);
                for(ResolveInfo info:context.getPackageManager().queryIntentActivities(intent,0)) launchable.add(info.activityInfo.packageName);
            }
            for(UsageReport.Entry entry:UsageReport.today(context,launchable,state.whitelist())) {
                JSONObject item=new JSONObject(); item.put("name",entry.label); item.put("package",entry.pkg); item.put("ms",entry.milliseconds); entries.put(item);
            }
        }
        result.put("apps",entries); result.put("permission",MainActivity.hasUsageAccess(context)); return result;
    }
    private static String line(InputStream in,int max) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream(); int c;
        while((c=in.read())!=-1) { if(c=='\n') break; if(c!='\r') b.write(c); if(b.size()>max) throw new IOException("header too large"); }
        return c==-1 && b.size()==0 ? null : new String(b.toByteArray(),StandardCharsets.UTF_8);
    }
    private void asset(Socket client,String name,String mime) throws IOException {
        try(InputStream file=context.getAssets().open(name)) {
            ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
            while((n=file.read(buffer))!=-1) out.write(buffer,0,n);
            replyBytes(client,200,mime,out.toByteArray());
        }
    }
    private static void reply(Socket client,int code,String mime,String data) throws IOException { replyBytes(client,code,mime+"; charset=utf-8",data.getBytes(StandardCharsets.UTF_8)); }
    private static void replyBytes(Socket client,int code,String mime,byte[] bytes) throws IOException {
        String status=code==200?"OK":code==401?"Unauthorized":code==404?"Not Found":"Bad Request";
        if(code==429) status="Too Many Requests";
        String headers="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: "+mime+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\nContent-Security-Policy: default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self'; frame-ancestors 'none'\r\n\r\n";
        OutputStream out=client.getOutputStream(); out.write(headers.getBytes(StandardCharsets.US_ASCII)); out.write(bytes); out.flush();
    }
    @Override public void close() {
        alive=false; try { if(server!=null) server.close(); } catch(IOException ignored) { }
        for(Runnable queued:requestPool.shutdownNow()) if(queued instanceof ClientTask) ((ClientTask)queued).closeSocket();
        jobPool.shutdownNow();
    }
}
