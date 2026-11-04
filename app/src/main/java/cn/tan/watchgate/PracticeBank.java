package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立的50题顺序练习：断点续答、永久题干去重、后台补题和最终成绩。 */
final class PracticeBank {
    static final int TARGET=50;
    // 每轮至少首次答对 45 题才通过 90% 正确率门槛。
    private static final int MIN_CORRECT=45;
    private static PracticeBank instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final AtomicBoolean generating=new AtomicBoolean(false),explaining=new AtomicBoolean(false);
    private volatile String supplyStatus="";
    private volatile long retryAfter=0;

    static synchronized PracticeBank get(Context context) {
        if(instance==null)instance=new PracticeBank(context);return instance;
    }
    private PracticeBank(Context context) {
        this.context=context.getApplicationContext();
        prefs=this.context.createDeviceProtectedStorageContext().getSharedPreferences("watch_gate_practice_v1",Context.MODE_PRIVATE);
        try {
            JSONObject session=read(),current=session==null?null:session.optJSONObject("current");
            if(current!=null&&"running".equals(current.optString("explainState"))) {
                current.put("explainState","failed");save(session);
            }
        } catch(Exception ignored) { }
    }
    private JSONObject read() throws Exception {
        String text=prefs.getString("session","");return text.isEmpty()?null:new JSONObject(text);
    }
    private void save(JSONObject session) throws Exception {
        if(!prefs.edit().putString("session",session.toString()).commit())throw new IllegalStateException("刷题进度保存失败，请检查设备存储");
    }
    private static String key(String stem) {return MathText.format(stem).replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
    private static String bankKey(JSONArray roots) throws Exception {
        List<String> ids=new ArrayList<>();for(int i=0;i<roots.length();i++)ids.add(roots.getString(i));Collections.sort(ids);return ids.toString();
    }

    boolean enabled(){return prefs.getBoolean("enabled",false);}
    synchronized void startByParent() throws Exception {
        JSONObject session=read();
        if(session==null)createRound(null);
        else if(session.getJSONArray("answers").length()>=TARGET&&!session.has("current"))createRound(session);
        if(!prefs.edit().putBoolean("enabled",true).commit())throw new IllegalStateException("无法开启刷题模式");
        supplyStatus="";retryAfter=0;
    }
    synchronized void stopByParent() throws Exception {
        if(!prefs.edit().putBoolean("enabled",false).commit())throw new IllegalStateException("无法停止刷题模式");
    }
    /** 保持刷题和题库操作的锁顺序，避免已删除题目留在待续练习中。 */
    synchronized JSONObject deleteQuestionBank(String source,JSONArray roots,int grade,String chapterId) throws Exception {
        if(enabled()||generating.get()||explaining.get()) throw new IllegalStateException("请先停止刷题并等待补题完成");
        JSONObject session=read(); boolean clearSession=false;
        if(session!=null) for(String key:new String[]{"inventory","seeds"}) {
            JSONArray items=session.optJSONArray(key); if(items==null) continue;
            for(int i=0;i<items.length();i++) {
                JSONObject q=items.optJSONObject(i);
                if(matchesBank(q,source,grade,chapterId)) clearSession=true;
            }
        }
        if(session!=null) {
            JSONObject current=session.optJSONObject("current");
            if(current!=null&&matchesBank(current.optJSONObject("question"),source,grade,chapterId)) clearSession=true;
            JSONArray answers=session.optJSONArray("answers");
            if(answers!=null) for(int i=0;i<answers.length();i++) {
                JSONObject answer=answers.optJSONObject(i);
                if(answer!=null&&matchesBank(answer.optJSONObject("question"),source,grade,chapterId)) clearSession=true;
            }
        }
        int count=QuestionBank.get(context).deleteQuestionBank(source,roots,grade,chapterId);
        if(clearSession&&!prefs.edit().remove("session").commit()) throw new IllegalStateException("题库已删除，但刷题进度清理失败");
        return new JSONObject().put("count",count).put("clearedPractice",clearSession);
    }
    private boolean matchesBank(JSONObject question,String source,int grade,String chapterId) {
        return question!=null&&source.equals(question.optString("sourceType"))&&
            ("__all__".equals(chapterId)&&!"chapter".equals(source)||question.optInt("grade")==grade&&chapterId.equals(question.optString("chapterId")));
    }
    synchronized JSONObject parentState() throws Exception {
        JSONObject session=read();JSONObject result=session==null?new JSONObject().put("answered",0).put("correct",0).put("wrong",0).put("score",0):score(session);
        result.remove("wrongs");result.remove("weaknesses");
        return result.put("enabled",enabled()).put("visible",PracticeActivity.visible)
            .put("complete",session!=null&&session.getJSONArray("answers").length()>=TARGET&&!session.has("current"));
    }

    private JSONObject createRound(JSONObject previous) throws Exception {
        JSONObject snapshot=QuestionBank.get(context).practiceSnapshot();JSONArray initial=snapshot.getJSONArray("questions"),roots=snapshot.getJSONArray("roots");
        boolean builtinFallback=initial.length()==0;
        if(builtinFallback) {
            initial=BuiltinPracticeQuestions.load(context);roots=new JSONArray();
            for(int i=0;i<initial.length();i++)roots.put(initial.getJSONObject(i).getString("id"));
        }
        if(initial.length()==0)throw new IllegalStateException("请家长先在管理端选择题库，并点击“应用该题库”");
        String selected=bankKey(roots);JSONArray inventory=new JSONArray();Set<String> ids=new HashSet<>();
        if(previous!=null&&selected.equals(previous.optString("bankKey"))) {
            JSONArray old=previous.getJSONArray("inventory");
            for(int i=0;i<old.length();i++){JSONObject q=old.getJSONObject(i);if(ids.add(q.getString("id")))inventory.put(q);}
        }
        for(int i=0;i<initial.length();i++){JSONObject q=initial.getJSONObject(i);if(ids.add(q.getString("id")))inventory.put(q);}
        JSONObject session=new JSONObject().put("id",UUID.randomUUID().toString()).put("bankKey",selected)
            .put("startedAt",System.currentTimeMillis()).put("answers",new JSONArray()).put("inventory",inventory).put("seeds",initial).put("seedCursor",0)
            .put("builtinFallback",builtinFallback);
        SharedPreferences.Editor edit=prefs.edit().putString("session",session.toString());
        if(previous!=null){JSONArray history=new JSONArray(prefs.getString("scores","[]"));history.put(score(previous));edit.putString("scores",history.toString());}
        if(!edit.commit())throw new IllegalStateException("无法创建刷题进度");supplyStatus="";retryAfter=0;return session;
    }
    private boolean unused(Question question,JSONObject used) throws Exception {
        return !used.has(key(question.title))&&!QuestionBank.get(context).practiceWasShown(question);
    }
    private int available(JSONObject session) throws Exception {
        JSONObject used=new JSONObject(prefs.getString("usedStems","{}"));JSONArray inventory=session.getJSONArray("inventory");int count=0;Set<String> stems=new HashSet<>();
        for(int i=0;i<inventory.length();i++){Question q=Question.fromStored(inventory.getJSONObject(i));if(stems.add(key(q.title))&&unused(q,used))count++;}
        return count;
    }
    /** 同一题库的新预备题并入进行中的刷题库存，题库切换时保留原轮内容。 */
    private boolean syncSharedInventory(JSONObject session) throws Exception {
        if(session.optBoolean("builtinFallback"))return false;
        QuestionBank bank=QuestionBank.get(context);JSONObject snapshot=bank.practiceSnapshot();
        if(!bankKey(snapshot.getJSONArray("roots")).equals(session.optString("bankKey")))return false;
        JSONArray incoming=snapshot.getJSONArray("questions"),inventory=session.getJSONArray("inventory");Set<String> ids=new HashSet<>();
        for(int i=0;i<inventory.length();i++)ids.add(inventory.getJSONObject(i).optString("id"));
        boolean changed=false;
        for(int i=0;i<incoming.length();i++) {
            JSONObject question=incoming.getJSONObject(i);
            if(ids.add(question.optString("id"))){inventory.put(question);changed=true;}
        }
        if(changed)save(session);
        return true;
    }
    private int needed(JSONObject session) throws Exception {
        JSONObject current=session.optJSONObject("current");return Math.max(0,TARGET-session.getJSONArray("answers").length()-(current!=null&&!current.optBoolean("submitted")?1:0)-available(session));
    }

    synchronized JSONObject view() throws Exception {
        if(!enabled())throw new IllegalStateException("家长已停止刷题");
        JSONObject session=read();if(session==null)session=createRound(null);
        boolean shared=syncSharedInventory(session);
        JSONArray answers=session.getJSONArray("answers");JSONObject current=session.optJSONObject("current");
        if(current==null&&answers.length()<TARGET) {
            JSONObject used=new JSONObject(prefs.getString("usedStems","{}"));JSONArray inventory=session.getJSONArray("inventory");
            for(int i=0;i<inventory.length();i++) {
                Question q=Question.fromStored(inventory.getJSONObject(i));if(!unused(q,used))continue;
                current=new JSONObject().put("question",q.toJson()).put("submitted",false).put("statsRecorded",false);
                session.put("current",current);used.put(key(q.title),true);
                if(!prefs.edit().putString("session",session.toString()).putString("usedStems",used.toString()).commit())throw new IllegalStateException("出题进度保存失败");break;
            }
        }
        if(current!=null&&!current.optBoolean("statsRecorded")) {
            Question q=Question.fromStored(current.getJSONObject("question"));
            QuestionBank.get(context).recordPractice(q,session.getString("id"),current.optBoolean("submitted")?current.getInt("choice"):-1);
            current.put("statsRecorded",true);save(session);
        }
        if(current!=null&&current.optBoolean("submitted"))requestExplanation(session,current);
        QuestionBank bank=QuestionBank.get(context);String sharedStatus=shared?bank.autoSupplyStatus():"";
        JSONObject result=score(session).put("complete",answers.length()>=TARGET&&current==null).put("current",current==null?JSONObject.NULL:current)
            .put("generating",generating.get()||shared&&bank.autoSupplyRunning())
            .put("supplyStatus",shared&&!sharedStatus.isEmpty()?sharedStatus:supplyStatus).put("available",available(session))
            .put("builtinFallback",session.optBoolean("builtinFallback"));
        return result;
    }

    synchronized void submit(String questionId,int choice) throws Exception {
        if(!enabled())throw new IllegalStateException("家长已停止刷题");
        JSONObject session=read(),current=session==null?null:session.optJSONObject("current");
        if(current==null)throw new IllegalStateException("当前没有可提交的题目");
        Question q=Question.fromStored(current.getJSONObject("question"));
        if(!questionId.equals(q.id))throw new IllegalStateException("题目已变化，请重新选择");
        if(current.optBoolean("submitted"))return;
        if(choice<0||choice>=q.choices.size())throw new IllegalArgumentException("请先选择答案");
        boolean correct=choice==q.answerIndex;
        current.put("choice",choice).put("submitted",true).put("correct",correct).put("statsRecorded",false);
        JSONObject answer=new JSONObject().put("question",q.toJson()).put("choice",choice).put("correct",correct).put("answeredAt",System.currentTimeMillis());
        session.getJSONArray("answers").put(answer);
        SharedPreferences.Editor edit=prefs.edit().putString("session",session.toString());
        if(!correct){JSONObject wrongs=new JSONObject(prefs.getString("mistakes","{}"));wrongs.put(q.id,answer);edit.putString("mistakes",wrongs.toString());}
        if(!edit.commit())throw new IllegalStateException("答题结果保存失败");
        view();
    }
    synchronized boolean advance() throws Exception {
        if(!enabled())throw new IllegalStateException("家长已停止刷题");
        JSONObject session=read(),current=session==null?null:session.optJSONObject("current");
        if(current==null||!current.optBoolean("submitted"))throw new IllegalStateException("请先提交本题");
        session.remove("current");
        if(session.getJSONArray("answers").length()==TARGET) {
            session.put("completedAt",System.currentTimeMillis());
            if(score(session).getInt("correct")<MIN_CORRECT) {
                createRound(session);
                return true;
            }
        }
        save(session);return false;
    }
    private JSONObject score(JSONObject session) throws Exception {
        JSONArray answers=session.getJSONArray("answers"),wrongs=new JSONArray();JSONObject weaknesses=new JSONObject();int correct=0;
        for(int i=0;i<answers.length();i++) {
            JSONObject answer=answers.getJSONObject(i);if(answer.optBoolean("correct")){correct++;continue;}
            wrongs.put(answer);JSONObject q=answer.getJSONObject("question");String topic=q.optString("subject")+" · "+q.optString("topic","综合知识");
            weaknesses.put(topic,weaknesses.optInt(topic,0)+1);
        }
        return new JSONObject().put("id",session.getString("id")).put("answered",answers.length()).put("correct",correct).put("wrong",wrongs.length())
            .put("score",correct*2).put("accuracy",answers.length()==0?0:Math.round(correct*100f/answers.length())).put("wrongs",wrongs).put("weaknesses",weaknesses)
            .put("startedAt",session.optLong("startedAt")).put("completedAt",session.optLong("completedAt",0));
    }

    void ensureSupply(boolean retry) {
        final String round;
        try {
            synchronized(this) {
                JSONObject session=read();if(!enabled()||session==null||session.getJSONArray("answers").length()>=TARGET)return;
                boolean shared=syncSharedInventory(session);
                if(needed(session)==0||available(session)>=10)return;
                if(shared){QuestionBank.get(context).requestAutoSupply(retry);return;}
                if(retry)retryAfter=0;
                if(System.currentTimeMillis()<retryAfter||!generating.compareAndSet(false,true))return;
                if(!ApiKeyVault.isConfigured(context)){supplyStatus="题库不足，请家长配置百炼 API Key 后重试";generating.set(false);retryAfter=System.currentTimeMillis()+30000;return;}
                round=session.getString("id");supplyStatus="正在补充未做过的新题…";
            }
        } catch(Exception e){supplyStatus="无法读取补题进度，请检查设备存储";return;}
        new Thread(() -> {
            try {
                while(true) {
                    Question seed;JSONArray avoid=new JSONArray();int count;
                    synchronized(this) {
                        JSONObject session=read();if(!enabled()||session==null||!round.equals(session.optString("id"))||session.getJSONArray("answers").length()>=TARGET||available(session)>=10)break;
                        count=Math.min(3,needed(session));if(count==0)break;
                        JSONArray seeds=session.getJSONArray("seeds");int cursor=session.optInt("seedCursor",0);seed=Question.fromStored(seeds.getJSONObject(cursor%seeds.length()));session.put("seedCursor",cursor+1);save(session);
                        JSONArray inventory=session.getJSONArray("inventory");for(int i=Math.max(0,inventory.length()-40);i<inventory.length();i++)avoid.put(inventory.getJSONObject(i).optString("stem"));
                    }
                    List<Question> generated=QwenClient.generatePracticeBatch(ApiKeyVault.read(context),seed,avoid,count,TextbookKnowledge.material(context,seed.chapterId));
                    if(!enabled())break;
                    JSONArray checks=new JSONArray();for(Question q:generated)checks.put(new JSONObject().put("id",q.id).put("subject",q.subject).put("stem",q.title).put("options",q.toJson().getJSONArray("options")));
                    supplyStatus="新题已生成，正在复核答案…";
                    JSONArray verified=QwenClient.verifyImportedQuestions(ApiKeyVault.read(context),checks);Map<String,JSONObject> byId=new HashMap<>();Set<String> repeated=new HashSet<>();
                    for(int i=0;i<verified.length();i++){JSONObject check=verified.optJSONObject(i);if(check!=null&&byId.put(check.optString("id"),check)!=null)repeated.add(check.optString("id"));}
                    List<Question> accepted=new ArrayList<>();
                    synchronized(this) {
                        JSONObject session=read();if(!enabled()||session==null||!round.equals(session.optString("id")))break;
                        Set<String> known=new HashSet<>();JSONArray inventory=session.getJSONArray("inventory");for(int i=0;i<inventory.length();i++)known.add(key(inventory.getJSONObject(i).optString("stem")));
                        JSONObject used=new JSONObject(prefs.getString("usedStems","{}"));
                        for(Question q:generated){JSONObject check=byId.get(q.id);if(check!=null&&!repeated.contains(q.id)&&check.optBoolean("valid")&&check.optInt("answerIndex",-1)==q.answerIndex&&known.add(key(q.title))&&unused(q,used))accepted.add(q.inChapter(seed.chapterId,seed.chapterTitle,seed.term,"practice").fromSource(seed.sourceType));}
                    }
                    if(accepted.isEmpty())throw new IllegalStateException("未获得通过核对且不重复的新题，请稍后重试");
                    List<Question> installed=QuestionBank.get(context).installPractice(accepted);
                    synchronized(this) {
                        JSONObject session=read();if(session==null||!round.equals(session.optString("id")))break;
                        JSONArray inventory=session.getJSONArray("inventory");Set<String> ids=new HashSet<>();for(int i=0;i<inventory.length();i++)ids.add(inventory.getJSONObject(i).optString("id"));
                        int added=0;for(Question q:installed)if(ids.add(q.id)){inventory.put(q.toJson());added++;}
                        if(added==0)throw new IllegalStateException("新题与已有题重复，请重试");save(session);supplyStatus="已补充 "+added+" 道新题";
                    }
                }
                retryAfter=0;
            } catch(Exception e){supplyStatus="补题失败："+(e.getMessage()==null?"请检查网络和百炼额度":e.getMessage());retryAfter=System.currentTimeMillis()+30000;}
            finally{generating.set(false);}
        },"practice-supply").start();
    }

    private void requestExplanation(JSONObject session,JSONObject current) throws Exception {
        Question question=Question.fromStored(current.getJSONObject("question"));
        if(!enabled()||!question.explanation.isEmpty()||!current.optString("explainState").isEmpty()||!ApiKeyVault.isConfigured(context)||!explaining.compareAndSet(false,true))return;
        String round=session.getString("id");current.put("explainState","running");save(session);
        new Thread(() -> {
            try {storeExplanation(round,question.id,QwenClient.explainPracticeQuestion(ApiKeyVault.read(context),question),true);}
            catch(Exception e){try{storeExplanation(round,question.id,"",false);}catch(Exception ignored){}}
            finally{explaining.set(false);}
        },"practice-explanation").start();
    }
    private synchronized void storeExplanation(String round,String id,String explanation,boolean success) throws Exception {
        JSONObject session=read();if(session==null||!round.equals(session.optString("id")))return;
        JSONArray answers=session.getJSONArray("answers"),inventory=session.getJSONArray("inventory");
        if(success){
            for(int i=0;i<answers.length();i++){JSONObject q=answers.getJSONObject(i).getJSONObject("question");if(id.equals(q.optString("id")))q.put("explanation",explanation);}
            for(int i=0;i<inventory.length();i++){JSONObject q=inventory.getJSONObject(i);if(id.equals(q.optString("id")))q.put("explanation",explanation);}
        }
        JSONObject current=session.optJSONObject("current");
        if(current!=null&&id.equals(current.getJSONObject("question").optString("id"))){current.put("explainState",success?"done":"failed");if(success)current.getJSONObject("question").put("explanation",explanation);}
        save(session);
        JSONObject wrongs=new JSONObject(prefs.getString("mistakes","{}"));if(success&&wrongs.has(id)){wrongs.getJSONObject(id).getJSONObject("question").put("explanation",explanation);prefs.edit().putString("mistakes",wrongs.toString()).commit();}
    }
}
