package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** 保存离线题包、答题进度和错题复习排期。 */
public final class QuestionBank {
    /** 每章先完成 10 道基础原题。 */
    public static final int BASE_TARGET=10;
    private static final int AUTO_SUPPLY_TARGET=10;
    private static final int AUTO_SUPPLY_BATCH=3;
    private static final String PREFS = "watch_gate_quiz_bank_v1";
    private static final String QUESTIONS = "questions";
    private static final String REVIEWS = "reviews";
    private static final String SESSION = "session";
    private static final String ACTIVE_GRADE = "active_grade";
    private static final String ACTIVE_CHAPTER = "active_chapter";
    private static final String APPLIED_ROOTS = "applied_bank_roots";
    private static final String PENDING_ROOTS = "pending_bank_roots";
    private static final String STATS = "question_stats_v1";
    private static final String SEEN_STEMS = "seen_question_stems_v1";
    private static final long[] INTERVALS = {
        10 * 60_000L, 1L * 86_400_000L, 2L * 86_400_000L,
        4L * 86_400_000L, 7L * 86_400_000L, 15L * 86_400_000L
    };
    private static volatile QuestionBank instance;
    private final SharedPreferences prefs;
    private final Context context;
    private final AtomicBoolean expansionRunning=new AtomicBoolean(false);
    private final AtomicBoolean basicGenerationRunning=new AtomicBoolean(false);
    private final AtomicBoolean reviewGenerationRunning=new AtomicBoolean(false);
    private final AtomicBoolean autoSupplyRunning=new AtomicBoolean(false);
    private volatile long reviewRetryAfter=0;
    private volatile String reviewStatus="";
    private volatile String basicStatus="";
    private volatile long basicRetryAfter=0;
    private volatile String expansionStatus="";
    private volatile long expansionRetryAfter=0;
    private volatile String expansionFailedChapter="";
    private volatile long autoSupplyNextCheck=0,autoSupplyRetryAfter=0;
    private volatile String autoSupplyStatus="";

    private static final class AutoSupplyPlan {
        final String selectedRoots,rootId;
        final Question seed;
        final JSONArray avoid;
        final int available,needed;
        AutoSupplyPlan(String selectedRoots,String rootId,Question seed,JSONArray avoid,int available,int needed) {
            this.selectedRoots=selectedRoots;this.rootId=rootId;this.seed=seed;this.avoid=avoid;this.available=available;this.needed=needed;
        }
    }

    public static QuestionBank get(Context context) {
        if (instance == null) synchronized (QuestionBank.class) {
            if (instance == null) instance = new QuestionBank(context.getApplicationContext());
        }
        return instance;
    }

    private QuestionBank(Context context) {
        this.context=context.getApplicationContext();
        prefs = context.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        discardUntrackedQuestions();
    }

    /** 按家长要求，只清除没有来源记录的旧题和关联答题数据，保留密钥及设备设置。 */
    private void discardUntrackedQuestions() {
        if(prefs.getBoolean("source_cleanup_v1",false)) return;
        try {
            JSONArray all=questions(),kept=new JSONArray(),keptReviews=new JSONArray();
            JSONObject oldStats=stats(),keptStats=new JSONObject(); Set<String> roots=new HashSet<>();
            for(int i=0;i<all.length();i++) {
                JSONObject raw=all.getJSONObject(i); if(raw.optString("sourceType").isEmpty()) continue;
                Question root=Question.fromStored(raw); kept.put(raw); roots.add(root.rootId);
                List<Question> group=new ArrayList<>(); group.add(root); group.addAll(root.variants);
                for(Question q:group) if(oldStats.has(q.id)) keptStats.put(q.id,oldStats.get(q.id));
            }
            JSONArray oldReviews=reviews();
            for(int i=0;i<oldReviews.length();i++) if(roots.contains(oldReviews.getJSONObject(i).optString("rootId"))) keptReviews.put(oldReviews.getJSONObject(i));
            SharedPreferences.Editor edit=prefs.edit().putBoolean("source_cleanup_v1",true);
            if(!prefs.contains(APPLIED_ROOTS)) edit.putString(APPLIED_ROOTS,"[]");
            if(kept.length()!=all.length()) edit.putString(QUESTIONS,kept.toString()).putString(STATS,keptStats.toString())
                .putString(REVIEWS,keptReviews.toString()).remove(SESSION).remove(SEEN_STEMS).putString(APPLIED_ROOTS,"[]").remove(PENDING_ROOTS).remove(ACTIVE_CHAPTER);
            if(!edit.commit()) throw new IllegalStateException("旧题清理保存失败");
        } catch(Exception e) { throw new IllegalStateException("无法清理未记录来源的旧题",e); }
    }

    /** 应用显示出的题目；原题的后续错题变体也属于同一个题库。 */
    public synchronized int applyQuestionBank(String source,JSONArray ids,int grade,String chapterId) throws Exception {
        if(!source.equals("image")&&!source.equals("document")&&!source.equals("chapter")) throw new IllegalArgumentException("请选择题库来源");
        if(ids==null||ids.length()==0) throw new IllegalArgumentException("当前没有可应用的题目");
        Set<String> requested=new HashSet<>(),roots=new HashSet<>(),matched=new HashSet<>();
        for(int i=0;i<ids.length();i++) requested.add(ids.getString(i));
        JSONArray all=questions();boolean allSource="__all__".equals(chapterId)&&!source.equals("chapter");
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i)); if(!source.equals(root.sourceType)||(!allSource&&(root.grade!=grade||!chapterId.equals(root.chapterId)))) continue;
            List<Question> group=new ArrayList<>(); group.add(root); group.addAll(root.variants);
            for(Question q:group) if(requested.contains(q.id)) { roots.add(root.rootId); matched.add(q.id); }
        }
        if(matched.size()!=requested.size()) throw new IllegalArgumentException("题库已变化，请刷新后重新应用");
        JSONArray selected=new JSONArray(); for(String id:roots) selected.put(id);
        if(!prefs.edit().putString(PENDING_ROOTS,selected.toString()).commit()) throw new IllegalStateException("题库应用保存失败");
        autoSupplyStatus="正在检查已应用题库的预备题";
        requestAutoSupply(true);
        return matched.size();
    }

    /** 应用题库后按低库存后台补题，避免等到弹题时才请求生成。 */
    public void requestAutoSupply(boolean force) {
        long now=System.currentTimeMillis();
        if(autoSupplyRunning.get()) return;
        if(force) { autoSupplyNextCheck=0; autoSupplyRetryAfter=0; }
        if(!force&&now<autoSupplyNextCheck) return;
        autoSupplyNextCheck=now+15_000L;
        if(now<autoSupplyRetryAfter) return;
        String selectedRoots=selectedRootState();
        if(selectedRoots.isEmpty()||"[]".equals(selectedRoots)) return;
        boolean configured;
        try { configured=ApiKeyVault.isConfigured(context); }
        catch(RuntimeException e) { autoSupplyStatus="无法读取百炼配置，请稍后重试";autoSupplyRetryAfter=now+60_000L;return; }
        if(!configured) {
            autoSupplyStatus="题库预备题不足，请家长配置百炼 API Key";
            autoSupplyRetryAfter=now+60_000L;
            return;
        }
        if(!autoSupplyRunning.compareAndSet(false,true)) return;
        autoSupplyStatus="正在检查已应用题库的预备题";
        try { new Thread(this::runAutoSupply,"quiz-auto-prefetch").start(); }
        catch(RuntimeException e) { autoSupplyRunning.set(false);autoSupplyStatus="自动补题未能启动，请稍后重试";autoSupplyRetryAfter=now+60_000L; }
    }

    public boolean autoSupplyRunning() { return autoSupplyRunning.get(); }
    public String autoSupplyStatus() { return autoSupplyStatus; }

    private String selectedRootState() {
        return prefs.getString(PENDING_ROOTS,prefs.getString(APPLIED_ROOTS,"[]"));
    }

    private void runAutoSupply() {
        try {
            for(int batch=0;batch<4;batch++) {
                AutoSupplyPlan plan;
                synchronized(this) { plan=buildAutoSupplyPlan(); }
                if(plan==null) {
                    autoSupplyStatus="请先应用题库，系统会自动准备未做过的新题";
                    break;
                }
                if(plan.needed==0) {
                    autoSupplyStatus="已准备好 "+plan.available+" 道未做题";
                    break;
                }
                autoSupplyStatus="正在提前准备题目（已有 "+plan.available+" 道）";
                List<Question> generated=QwenClient.generatePracticeBatch(ApiKeyVault.read(context),plan.seed,plan.avoid,
                    plan.needed,TextbookKnowledge.material(context,plan.seed.chapterId));
                if(generated==null||generated.isEmpty()) throw new IllegalStateException("没有生成新题");
                JSONArray checks=new JSONArray();
                for(Question q:generated) checks.put(new JSONObject().put("id",q.id).put("subject",q.subject)
                    .put("stem",q.title).put("options",q.toJson().getJSONArray("options")));
                autoSupplyStatus="新题已生成，正在复核答案";
                JSONArray verified=QwenClient.verifyImportedQuestions(ApiKeyVault.read(context),checks);
                Map<String,JSONObject> byId=new HashMap<>();Set<String> repeated=new HashSet<>();
                for(int i=0;i<verified.length();i++) {
                    JSONObject row=verified.optJSONObject(i);
                    if(row!=null&&byId.put(row.optString("id"),row)!=null) repeated.add(row.optString("id"));
                }
                List<Question> accepted=new ArrayList<>();Set<String> stems=new HashSet<>();
                for(Question q:generated) {
                    JSONObject row=byId.get(q.id);
                    if(row!=null&&!repeated.contains(q.id)&&row.optBoolean("valid")
                            &&row.optInt("answerIndex",-1)==q.answerIndex&&stems.add(stemKey(q.title))) {
                        accepted.add(q);
                        if(accepted.size()>=plan.needed) break;
                    }
                }
                if(accepted.isEmpty()) throw new IllegalStateException("没有通过答案复核的新题");
                int added;
                synchronized(this) { added=appendAutoPractice(plan,accepted); }
                if(added<0) { autoSupplyStatus="题库已更换，本批预备题未保存";break; }
                if(added==0) throw new IllegalStateException("新题与已有题重复");
                autoSupplyStatus="已提前准备 "+added+" 道新题";
            }
            autoSupplyRetryAfter=0;
        } catch(Exception e) {
            String detail=e.getMessage();
            autoSupplyStatus="自动补题失败："+(detail==null||detail.trim().isEmpty()?"请检查网络和百炼额度":detail);
            autoSupplyRetryAfter=System.currentTimeMillis()+300_000L;
        } finally {
            autoSupplyNextCheck=System.currentTimeMillis()+15_000L;
            autoSupplyRunning.set(false);
        }
    }

    /** 统计已应用题库中真正未出现、且可供两种答题模式共用的新题。 */
    private AutoSupplyPlan buildAutoSupplyPlan() throws Exception {
        String selectedText=selectedRootState();
        JSONArray selected=new JSONArray(selectedText);if(selected.length()==0)return null;
        Set<String> ids=new HashSet<>();for(int i=0;i<selected.length();i++)ids.add(selected.getString(i));
        Set<String> wrongRoots=new HashSet<>();JSONArray wrong=reviews();
        for(int i=0;i<wrong.length();i++)wrongRoots.add(wrong.getJSONObject(i).optString("rootId"));
        JSONObject allStats=stats(),seen=seenStems(),session=readSession();
        Set<String> queuedIds=new HashSet<>(),queuedStems=new HashSet<>();
        if(session!=null) {
            JSONArray items=session.optJSONArray("items");
            if(items!=null) for(int i=0;i<items.length();i++) {
                JSONObject raw=items.getJSONObject(i).optJSONObject("question");if(raw==null)continue;
                queuedIds.add(raw.optString("id"));queuedStems.add(stemKey(raw.optString("stem","")));
            }
        }
        JSONArray all=questions(),avoid=new JSONArray();List<Question> roots=new ArrayList<>();Set<String> avoidKeys=new HashSet<>(),counted=new HashSet<>();
        int available=0;
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i));if(!ids.contains(root.rootId))continue;
            roots.add(root);
            List<Question> group=new ArrayList<>();group.add(root);group.addAll(root.variants);
            for(Question q:group) {
                String key=stemKey(q.title);
                if(avoid.length()<50&&avoidKeys.add(key))avoid.put(q.title);
                boolean mayServe=q.id.equals(root.id)||"practice".equals(q.questionType)||wrongRoots.contains(root.rootId);
                JSONObject stat=allStats.optJSONObject(q.id);
                boolean fresh=stat!=null&&stat.optBoolean("historyKnown",false)&&stat.optInt("shown",0)==0
                    &&!seen.has(key)&&!queuedIds.contains(q.id)&&!queuedStems.contains(key);
                if(mayServe&&fresh&&counted.add(key))available++;
            }
        }
        int needed=Math.min(AUTO_SUPPLY_BATCH,Math.max(0,AUTO_SUPPLY_TARGET-available));
        if(needed==0)return new AutoSupplyPlan(selectedText,"",null,avoid,available,0);
        if(roots.isEmpty())return null;
        Question seed=roots.get(ThreadLocalRandom.current().nextInt(roots.size()));
        return new AutoSupplyPlan(selectedText,seed.rootId,seed,avoid,available,needed);
    }

    /** 只把复核通过的预备题挂到原题库下；题库切换期间生成的结果会被丢弃。 */
    private int appendAutoPractice(AutoSupplyPlan plan,List<Question> generated) throws Exception {
        if(!plan.selectedRoots.equals(selectedRootState()))return -1;
        JSONArray all=questions();JSONObject allStats=stats(),seen=seenStems();int rootIndex=-1;JSONObject rootJson=null;
        Set<String> stems=new HashSet<>();
        for(int i=0;i<all.length();i++) {
            JSONObject raw=all.getJSONObject(i);Question root=Question.fromStored(raw);stems.add(stemKey(root.title));
            for(Question variant:root.variants)stems.add(stemKey(variant.title));
            if(root.rootId.equals(plan.rootId)){rootIndex=i;rootJson=raw;}
        }
        if(rootIndex<0||rootJson==null)return -1;
        Question root=Question.fromStored(rootJson);JSONArray variants=rootJson.optJSONArray("variants");if(variants==null)variants=new JSONArray();int added=0;
        for(Question candidate:generated) {
            String key=stemKey(candidate.title);if(!stems.add(key)||seen.has(key))continue;
            Question variant=candidate.inChapter(root.chapterId,root.chapterTitle,root.term,"practice")
                .fromSource(root.sourceType).withRootId(root.rootId);
            variants.put(variant.toJson());initializeStats(allStats,variant);added++;
        }
        if(added==0)return 0;
        rootJson.put("variants",variants);all.put(rootIndex,rootJson);
        // 版本与题目一起保存，让网页即使错过生成过程也能发现新增题目。
        long revision=prefs.getLong("auto_supply_revision",0L)+1;
        if(!prefs.edit().putString(QUESTIONS,all.toString()).putString(STATS,allStats.toString()).putLong("auto_supply_revision",revision).commit())
            throw new IllegalStateException("预备题保存失败");
        return added;
    }

    /** 删除当前章节和来源的整套题，保留历史统计及已出现题干记录。 */
    synchronized int deleteQuestionBank(String source,JSONArray expectedRoots,int grade,String chapterId) throws Exception {
        if(!source.equals("image")&&!source.equals("document")&&!source.equals("chapter")) throw new IllegalArgumentException("请选择题库来源");
        if(expectedRoots==null||expectedRoots.length()==0) throw new IllegalArgumentException("当前题库没有可删除的题目");
        JSONObject session=readSession();
        if(session!=null&&remaining(session)>0) throw new IllegalStateException("当前答题尚未结束，请先完成或家长放行");
        if(preparingQuestions()) throw new IllegalStateException("题目正在生成，请完成后再删除");
        Set<String> expected=new HashSet<>(),removed=new HashSet<>();
        for(int i=0;i<expectedRoots.length();i++) expected.add(expectedRoots.getString(i));
        JSONArray all=questions(),kept=new JSONArray();boolean allSource="__all__".equals(chapterId)&&!source.equals("chapter");
        for(int i=0;i<all.length();i++) {
            JSONObject raw=all.getJSONObject(i); Question q=Question.fromStored(raw);
            if(source.equals(q.sourceType)&&(allSource||(q.aiGenerated&&q.grade==grade&&chapterId.equals(q.chapterId)))) removed.add(q.rootId);
            else kept.put(raw);
        }
        if(!removed.equals(expected)) throw new IllegalStateException("题库内容已变化，请刷新后重新确认");
        JSONArray keptReviews=new JSONArray(),oldReviews=reviews();
        for(int i=0;i<oldReviews.length();i++) if(!removed.contains(oldReviews.getJSONObject(i).optString("rootId"))) keptReviews.put(oldReviews.getJSONObject(i));
        SharedPreferences.Editor edit=prefs.edit().putString(QUESTIONS,kept.toString()).putString(REVIEWS,keptReviews.toString());
        for(String name:new String[]{APPLIED_ROOTS,PENDING_ROOTS}) if(prefs.contains(name)) {
            JSONArray roots=new JSONArray(prefs.getString(name,"[]")),remainingRoots=new JSONArray();
            for(int i=0;i<roots.length();i++) if(!removed.contains(roots.getString(i))) remainingRoots.put(roots.getString(i));
            edit.putString(name,remainingRoots.toString());
        }
        if(!edit.commit()) throw new IllegalStateException("删除题库保存失败");
        return removed.size();
    }

    private void activatePendingBank() {
        if(!prefs.contains(PENDING_ROOTS)) return;
        if(!prefs.edit().putString(APPLIED_ROOTS,prefs.getString(PENDING_ROOTS,"[]")).remove(PENDING_ROOTS).commit()) throw new IllegalStateException("题库切换失败");
    }

    private boolean belongsToAppliedBank(Question question) throws Exception {
        // 题库完全为空时，内置备用题也要能通过普通答题队列的新题检查。
        if("system".equals(question.sourceType)) return questions().length()==0;
        if(!prefs.contains(APPLIED_ROOTS)) return true;
        JSONArray roots=new JSONArray(prefs.getString(APPLIED_ROOTS,"[]"));
        for(int i=0;i<roots.length();i++) if(question.rootId.equals(roots.getString(i))) return true;
        return false;
    }

    /** 家长明确要求清空本机旧题；保留密钥、设备设置及不含题干的历史统计。 */
    synchronized void clearRequestedQuestions(String requestHash) throws Exception {
        String marker="parent_clear_"+requestHash;
        if(prefs.getBoolean(marker,false))return;
        Context storage=context.createDeviceProtectedStorageContext();
        SharedPreferences practice=storage.getSharedPreferences("watch_gate_practice_v1",Context.MODE_PRIVATE);
        JSONArray history=new JSONArray(practice.getString("scores","[]")),summary=new JSONArray();
        for(int i=0;i<history.length();i++)summary.put(practiceScoreSummary(history.getJSONObject(i)));
        String currentText=practice.getString("session","");
        if(!currentText.isEmpty()){
            JSONObject session=new JSONObject(currentText);JSONArray answers=session.optJSONArray("answers");int correct=0;
            if(answers!=null&&answers.length()>0){
                for(int i=0;i<answers.length();i++)if(answers.getJSONObject(i).optBoolean("correct"))correct++;
                summary.put(new JSONObject().put("id",session.optString("id")).put("answered",answers.length()).put("correct",correct)
                    .put("wrong",answers.length()-correct).put("score",correct*2).put("startedAt",session.optLong("startedAt")).put("completedAt",session.optLong("completedAt")));
            }
        }
        if(!practice.edit().putBoolean("enabled",false).remove("session").remove("usedStems").remove("mistakes").putString("scores",summary.toString()).commit())
            throw new IllegalStateException("无法清理刷题进度");
        SharedPreferences generation=storage.getSharedPreferences("watch_gate_generation_status",Context.MODE_PRIVATE);
        if(!generation.edit().remove("chapter").putString("status","尚未生成题库").putBoolean("running",false).commit())throw new IllegalStateException("无法清理生成状态");
        SharedPreferences.Editor edit=prefs.edit();
        if(prefs.contains(STATS))edit.putString("archived_stats_"+requestHash.substring(0,16),prefs.getString(STATS,"{}"));
        edit.remove(QUESTIONS).remove(REVIEWS).remove(SESSION).remove(STATS).remove(SEEN_STEMS).remove(ACTIVE_CHAPTER)
            .remove(PENDING_ROOTS).remove("practice_events").putString(APPLIED_ROOTS,"[]").putBoolean(marker,true);
        if(!edit.commit())throw new IllegalStateException("无法清空题库");
    }
    private static JSONObject practiceScoreSummary(JSONObject source) throws Exception {
        JSONObject result=new JSONObject();
        for(String key:new String[]{"id","answered","correct","wrong","score","accuracy","startedAt","completedAt"})if(source.has(key))result.put(key,source.get(key));
        return result;
    }

    public synchronized int install(List<Question> incoming) throws Exception {
        JSONArray stored = questions(); JSONObject stats=stats(); Set<String> signatures = new HashSet<>();
        for (int i = 0; i < stored.length(); i++) {
            JSONObject q = stored.getJSONObject(i);
            signatures.add(q.optString("sourceType","")+":"+signature(q.optString("chapterId",""),q.optInt("grade", 6), q.optString("subject"), q.optString("topic"), q.optString("stem")));
        }
        for (Question q : incoming) {
            if(q.sourceType.isEmpty()) q.fromSource("chapter");
            String signature = q.sourceType+":"+signature(q.chapterId,q.grade, q.subject, q.topic, q.title);
            if (signatures.add(signature)) {
                stored.put(q.toJson()); initializeStats(stats,q);
                for(Question variant:q.variants) initializeStats(stats,variant);
            }
        }
        if (!prefs.edit().putString(QUESTIONS, stored.toString()).putString(STATS,stats.toString()).commit()) throw new IllegalStateException("无法保存题库");
        return stored.length();
    }

    /** 同来源内按年级和题干去重，保留图片、文档各自独立的题库。 */
    public synchronized int installRecognized(List<Question> incoming) throws Exception {
        Set<String> known=new HashSet<>(),knownIds=new HashSet<>(); JSONArray stored=questions();
        for(int i=0;i<stored.length();i++) {
            Question root=Question.fromStored(stored.getJSONObject(i));
            knownIds.add(root.id); known.add(root.sourceType+":"+root.grade+":"+stemKey(root.title));
            for(Question variant:root.variants) known.add(root.sourceType+":"+variant.grade+":"+stemKey(variant.title));
        }
        List<Question> unique=new ArrayList<>();
        for(Question question:incoming) if(knownIds.add(question.id)&&known.add(question.sourceType+":"+question.grade+":"+stemKey(question.title))) unique.add(question);
        if(unique.isEmpty()) return 0;
        int before=stored.length(); install(unique); return questions().length()-before;
    }

    /** 专项题进入普通题库；正在进行的答题在当前题后插入一道。 */
    public synchronized int installExamPractice(List<Question> incoming) throws Exception {
        Set<String> before=new HashSet<>(); JSONArray old=questions();
        for(int i=0;i<old.length();i++) before.add(old.getJSONObject(i).optString("id"));
        install(incoming);
        JSONArray all=questions(); int added=0; Question first=null;
        for(int i=0;i<all.length();i++) {
            JSONObject raw=all.getJSONObject(i);
            if(!before.contains(raw.optString("id")) && "exam".equals(raw.optString("questionType"))) {
                added++; if(first==null) first=Question.fromStored(raw);
            }
        }
        JSONObject session=readSession();
        if(first!=null && belongsToAppliedBank(first) && session!=null && !session.optBoolean("completePending")) {
            JSONArray items=session.optJSONArray("items");
            if(items!=null && items.length()>0) {
                JSONArray updated=new JSONArray(); updated.put(items.getJSONObject(0)); updated.put(item(first,false));
                for(int i=1;i<items.length();i++) updated.put(items.getJSONObject(i));
                session.put("items",updated); saveSession(session);
            }
        }
        return added;
    }

    private void initializeStats(JSONObject stats,Question question) throws Exception {
        if(!stats.has(question.id)) stats.put(question.id,new JSONObject().put("historyKnown",true).put("shown",0).put("answers",0).put("correct",0).put("wrong",0).put("timeout",0));
    }

    /** 原题下追加新变体，保留原错题的复习排期。 */
    private synchronized int installReviewVariants(String rootId,List<Question> generated) throws Exception {
        JSONArray all=questions(); JSONObject allStats=stats(); int rootIndex=-1; JSONObject rootJson=null;
        Set<String> stems=new HashSet<>();
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i)); stems.add(stemKey(root.title));
            for(Question variant:root.variants) stems.add(stemKey(variant.title));
            if(root.id.equals(rootId)) { rootIndex=i; rootJson=all.getJSONObject(i); }
        }
        if(rootIndex<0||rootJson==null) throw new IllegalStateException("错题不存在");
        Question root=Question.fromStored(rootJson); JSONArray variants=rootJson.optJSONArray("variants"); if(variants==null) variants=new JSONArray();
        int added=0;
        for(Question generatedRoot:generated) {
            generatedRoot.inChapter(root.chapterId,root.chapterTitle,root.term,root.questionType).fromSource(root.sourceType);
            List<Question> candidates=new ArrayList<>(); candidates.add(generatedRoot); candidates.addAll(generatedRoot.variants);
            for(Question candidate:candidates) if(stems.add(stemKey(candidate.title))) {
                Question variant=candidate.withRootId(rootId); variants.put(variant.toJson()); initializeStats(allStats,variant); added++;
            }
        }
        if(added==0) throw new IllegalStateException("没有生成新的变体");
        rootJson.put("variants",variants); all.put(rootIndex,rootJson);
        if(!prefs.edit().putString(QUESTIONS,all.toString()).putString(STATS,allStats.toString()).commit()) throw new IllegalStateException("无法保存变体");
        return added;
    }

    private void requestReviewVariants(String rootId) {
        // 只有已记录为错题的原题才能触发变体生成。
        try { if(findReview(rootId)==null) return; } catch(Exception e) { return; }
        if(System.currentTimeMillis()<reviewRetryAfter||!reviewGenerationRunning.compareAndSet(false,true)) return;
        if(!ApiKeyVault.isConfigured(context)) { reviewStatus="请由家长设置百炼 API Key"; reviewGenerationRunning.set(false); return; }
        reviewStatus="正在生成新的题目变体";
        new Thread(() -> {
            try {
                Question root; JSONArray previous=new JSONArray();
                synchronized(this) {
                    JSONObject json=findRoot(rootId); if(json==null) throw new IllegalStateException("错题不存在");
                    root=Question.fromStored(json); previous.put(root.title);
                    for(Question variant:root.variants) previous.put(variant.title);
                }
                String material=TextbookKnowledge.material(context,root.chapterId);
                List<Question> generated=QwenClient.generateReviewVariants(ApiKeyVault.read(context),root,previous,material);
                if(!material.isEmpty()) {
                    JSONArray input=new JSONArray();for(Question q:generated)input.put(new JSONObject().put("id",q.id).put("subject",q.subject).put("stem",q.title).put("options",q.toJson().getJSONArray("options")));
                    JSONArray checks=QwenClient.verifyImportedQuestions(ApiKeyVault.read(context),input);Map<String,JSONObject> byId=new HashMap<>();Set<String> duplicates=new HashSet<>();
                    for(int i=0;i<checks.length();i++){JSONObject row=checks.optJSONObject(i);if(row!=null&&byId.put(row.optString("id"),row)!=null)duplicates.add(row.optString("id"));}
                    List<Question> verified=new ArrayList<>();for(Question q:generated){JSONObject row=byId.get(q.id);if(row!=null&&!duplicates.contains(q.id)&&row.optBoolean("valid")&&row.optInt("answerIndex",-1)==q.answerIndex)verified.add(q);}
                    generated=verified;
                }
                int count=installReviewVariants(rootId,generated); reviewStatus="已生成 "+count+" 道新变体"; reviewRetryAfter=0;
            } catch(Exception e) { reviewStatus="变体生成失败，请家长检查网络和百炼额度，稍后自动重试"; reviewRetryAfter=System.currentTimeMillis()+300_000L; }
            finally { reviewGenerationRunning.set(false); }
        },"review-variants").start();
    }

    public synchronized void setActiveChapter(int grade,String chapterId) {
        if(grade<1||grade>12||chapterId==null||chapterId.isEmpty()) throw new IllegalArgumentException("章节无效");
        prefs.edit().putInt(ACTIVE_GRADE,grade).putString(ACTIVE_CHAPTER,chapterId).commit();
    }

    public synchronized void setChapterPreferences(String chapterId,String difficulty,String guidance) throws Exception {
        JSONObject config=new JSONObject().put("difficulty",difficulty).put("guidance",guidance);
        if(!prefs.edit().putString("chapter_config_"+chapterId,config.toString()).commit()) throw new IllegalStateException("无法保存章节设置");
    }

    private String activeChapter() { return prefs.contains(APPLIED_ROOTS)?"":prefs.getString(ACTIVE_CHAPTER,""); }

    private int unusedExtensions(String chapterId) {
        JSONArray all=questions(); JSONObject stat=stats(); int count=0;
        for(int i=0;i<all.length();i++) {
            JSONObject q=all.optJSONObject(i); if(q==null||!chapterId.equals(q.optString("chapterId"))||!"extension".equals(q.optString("questionType"))) continue;
            try { if(isFresh(Question.fromStored(q))) count++; } catch(Exception ignored) { }
        }
        return count;
    }

    /** 完成基础阶段后，低库存时后台补充拓展题，失败时稍后重试。 */
    private void maybeGenerateExpansion() {
        final String chapterId=activeChapter(); final int grade=activeGrade();
        if(chapterId.isEmpty()||baseCount(chapterId)<BASE_TARGET||baseAnswered(chapterId)<BASE_TARGET||unusedExtensions(chapterId)>=3) return;
        if(!ApiKeyVault.isConfigured(context)) { expansionStatus="请先设置百炼 API Key"; return; }
        long now=System.currentTimeMillis();
        if((chapterId.equals(expansionFailedChapter)&&now<expansionRetryAfter)||!expansionRunning.compareAndSet(false,true)) return;
        expansionStatus="正在补充拓展题";
        new Thread(() -> {
            try {
                JSONObject found=TextbookKnowledge.chapter(context,chapterId); String foundTerm=found==null?"上":found.optString("term","上");
                if(found==null) throw new IllegalStateException("当前教材中没有该章节");
                final JSONObject chapter=found; final String term=foundTerm;
                int before=unusedExtensions(chapterId); String avoid="避免重复已有题干："+chapterStems(chapterId);
                JSONObject config=new JSONObject(prefs.getString("chapter_config_"+chapterId,"{}"));
                QwenClient.generateChapter(ApiKeyVault.read(context),grade,term,chapter,config.optString("difficulty","advanced"),config.optString("guidance",""),3,"extension",avoid,new QwenClient.Progress() {
                    @Override public void update(String message) { expansionStatus=message; }
                    @Override public void subjectReady(String subject,List<Question> questions) throws Exception {
                        for(Question q:questions) q.inChapter(chapterId,chapter.optString("title"),term,"extension");
                        install(questions);
                    }
                });
                if(unusedExtensions(chapterId)<=before) throw new IllegalStateException("没有新增题目");
                expansionRetryAfter=0; expansionFailedChapter=""; expansionStatus="拓展题已补充";
            } catch(Exception e) { expansionFailedChapter=chapterId; expansionRetryAfter=System.currentTimeMillis()+300_000L; expansionStatus="拓展题补充失败，稍后重试"; }
            finally { expansionRunning.set(false); }
        },"chapter-extension").start();
    }

    /** 基础题未补齐时后台续生，避免等在空题库。 */
    private void requestMissingBasics(String chapterId,int grade) {
        if(System.currentTimeMillis()<basicRetryAfter||!basicGenerationRunning.compareAndSet(false,true)) return;
        if(!ApiKeyVault.isConfigured(context)) { basicStatus="基础题生成失败：请由家长设置百炼 API Key"; basicGenerationRunning.set(false); return; }
        basicStatus="正在补充基础题";
        new Thread(() -> {
            try {
                JSONObject found=TextbookKnowledge.chapter(context,chapterId); String foundTerm=found==null?"上":found.optString("term","上");
                if(found==null) throw new IllegalStateException("当前教材中没有该章节");
                final JSONObject chapter=found; final String term=foundTerm;
                for(int attempt=0;attempt<8&&baseCount(chapterId)<BASE_TARGET;attempt++) {
                    int before=baseCount(chapterId),needed=Math.min(3,BASE_TARGET-before);
                    JSONObject config=new JSONObject(prefs.getString("chapter_config_"+chapterId,"{}"));
                    QwenClient.generateChapter(ApiKeyVault.read(context),grade,term,chapter,"basic",config.optString("guidance",""),needed,"base",
                        "避免重复现有题干："+chapterStems(chapterId),new QwenClient.Progress() {
                            @Override public void update(String message) { basicStatus=message; }
                            @Override public void subjectReady(String subject,List<Question> generated) throws Exception {
                                for(Question q:generated) q.inChapter(chapterId,chapter.optString("title"),term,"base");
                                install(generated);
                            }
                        });
                    if(baseCount(chapterId)<=before) throw new IllegalStateException("没有新增基础题");
                }
                if(baseCount(chapterId)<BASE_TARGET) throw new IllegalStateException("基础题数量不足");
                basicStatus="基础题已补充"; basicRetryAfter=0;
            } catch(Exception e) { basicStatus="基础题补充失败，请家长检查网络和百炼额度，稍后自动重试"; basicRetryAfter=System.currentTimeMillis()+300_000L; }
            finally { basicGenerationRunning.set(false); }
        },"chapter-basics").start();
    }

    public synchronized int baseCount(String chapterId) {
        JSONArray all=questions(); int count=0;
        for(int i=0;i<all.length();i++) { JSONObject q=all.optJSONObject(i); if(q!=null && "chapter".equals(q.optString("sourceType")) && chapterId.equals(q.optString("chapterId")) && "base".equals(q.optString("questionType","base"))) count++; }
        return Math.min(count,BASE_TARGET);
    }

    public synchronized int baseAnswered(String chapterId) {
        JSONArray all=questions(); JSONObject stat=stats(); int count=0,considered=0;
        for(int i=0;i<all.length();i++) {
            JSONObject q=all.optJSONObject(i); if(q==null||!"chapter".equals(q.optString("sourceType"))||!chapterId.equals(q.optString("chapterId"))||!"base".equals(q.optString("questionType","base"))) continue;
            if(considered++>=BASE_TARGET) break;
            JSONObject s=stat.optJSONObject(q.optString("id")); boolean answered=s!=null&&s.optInt("answers",0)>0;
            JSONArray variants=q.optJSONArray("variants");
            if(!answered&&variants!=null) for(int j=0;j<variants.length();j++) {
                JSONObject variant=variants.optJSONObject(j),vs=variant==null?null:stat.optJSONObject(variant.optString("id"));
                if(vs!=null&&vs.optInt("answers",0)>0) { answered=true; break; }
            }
            if(answered) count++;
        }
        return count;
    }

    public synchronized JSONArray uncoveredConcepts(String chapterId,JSONArray concepts) {
        Set<String> covered=new HashSet<>(); JSONArray all=questions();
        for(int i=0;i<all.length();i++) {
            JSONObject q=all.optJSONObject(i); if(q==null||!"chapter".equals(q.optString("sourceType"))||!chapterId.equals(q.optString("chapterId"))||!"base".equals(q.optString("questionType","base"))) continue;
            JSONArray points=q.optJSONArray("knowledgePoints");
            if(points!=null) for(int j=0;j<points.length();j++) covered.add(points.optString(j,"").trim());
        }
        JSONArray missing=new JSONArray(); for(int i=0;i<concepts.length();i++) { String point=concepts.optString(i,"").trim(); if(!point.isEmpty()&&!covered.contains(point)) missing.put(point); }
        return missing;
    }

    public synchronized JSONArray chapterStems(String chapterId) {
        JSONArray result=new JSONArray(),all=questions();
        for(int i=Math.max(0,all.length()-60);i<all.length();i++) { JSONObject q=all.optJSONObject(i); if(q!=null&&chapterId.equals(q.optString("chapterId"))) result.put(q.optString("stem")); }
        return result;
    }

    public synchronized JSONObject questionTable(int grade,String chapterId,String sourceType) throws Exception {
        JSONArray rows=new JSONArray(),all=questions(); JSONObject stat=stats();
        int unknown=0,unseen=0,correct=0,wrong=0,timeout=0,allBase=0;
        boolean allChapters="__all__".equals(chapterId);
        for(int i=0;i<all.length();i++) {
            JSONObject stored=all.getJSONObject(i); Question root=Question.fromStored(stored);
            if(!sourceType.isEmpty()&&!sourceType.equals(root.sourceType)) continue;
            boolean legacy=chapterId.equals("__legacy__") && root.chapterId.isEmpty();
            if(!allChapters && (!root.aiGenerated||root.grade!=grade||(!legacy&&!chapterId.equals(root.chapterId)))) continue;
            if("base".equals(root.questionType)) allBase++;
            List<Question> group=new ArrayList<>(); group.add(root); group.addAll(root.variants);
            for(Question q:group) {
                JSONObject s=stat.optJSONObject(q.id); boolean known=s!=null&&s.optBoolean("historyKnown",false);
                int shown=s==null?0:s.optInt("shown"),right=s==null?0:s.optInt("correct"),bad=s==null?0:s.optInt("wrong"),late=s==null?0:s.optInt("timeout");
                if(!known) unknown++; else if(shown==0) unseen++;
                if(right>0) correct++; if(bad>0) wrong++; if(late>0) timeout++;
                JSONArray options=new JSONArray(); for(String choice:q.choices) options.put(choice);
                JSONObject review=findReview(q.rootId);
                JSONObject row=new JSONObject().put("id",q.id).put("rootId",q.rootId).put("stem",q.title).put("topic",q.topic)
                    .put("kind",q.id.equals(q.rootId)?q.questionType:("practice".equals(q.questionType)?"auto_practice":"variant")).put("historyKnown",known)
                    .put("shown",shown).put("correct",right).put("wrong",bad).put("timeout",late)
                    .put("choices",options).put("answer",q.choices.get(q.answerIndex)).put("nextReview",review==null?0:review.optLong("dueAt",0));
                rows.put(row);
            }
        }
        return new JSONObject().put("rows",rows).put("total",rows.length()).put("unknown",unknown).put("unseen",unseen)
            .put("correct",correct).put("wrong",wrong).put("timeout",timeout)
            .put("baseCount",allBase).put("baseAnswered",allChapters?0:baseAnswered(chapterId)).put("expansionStatus",expansionStatus);
    }

    public synchronized void markShown(String questionId) throws Exception {
        JSONObject session=readSession(); if(session==null) return;
        JSONArray items=session.optJSONArray("items"); if(items==null||items.length()==0) return;
        JSONObject first=items.getJSONObject(0),q=first.getJSONObject("question");
        if(!questionId.equals(q.optString("id"))||first.optBoolean("shownRecorded")) return;
        JSONObject all=stats(),s=all.optJSONObject(questionId); if(s==null) s=new JSONObject();
        s.put("shown",s.optInt("shown",0)+1).put("lastShown",System.currentTimeMillis()); all.put(questionId,s);
        first.put("shownRecorded",true); items.put(0,first); session.put("items",items);
        JSONObject stems=seenStems(); stems.put(stemKey(q.optString("stem","")),true);
        if(!prefs.edit().putString(STATS,all.toString()).putString(SESSION,session.toString()).putString(SEEN_STEMS,stems.toString()).commit()) throw new IllegalStateException("无法保存出题记录");
        requestAutoSupply(false);
    }

    private void recordResult(Question q,String outcome) throws Exception {
        JSONObject all=stats(),s=all.optJSONObject(q.id); if(s==null) s=new JSONObject();
        if(!outcome.equals("timeout")) s.put("answers",s.optInt("answers",0)+1);
        s.put(outcome,s.optInt(outcome,0)+1).put("lastResult",outcome).put("lastAt",System.currentTimeMillis());
        all.put(q.id,s); if(!prefs.edit().putString(STATS,all.toString()).commit()) throw new IllegalStateException("无法保存答题记录");
        if(!outcome.equals("timeout")) maybeGenerateExpansion();
    }

    public synchronized void setActiveGrade(int grade) {
        if (grade < 1 || grade > 12) throw new IllegalArgumentException("年级无效");
        prefs.edit().putInt(ACTIVE_GRADE, grade).commit();
    }

    private int activeGrade() { return prefs.getInt(ACTIVE_GRADE, 6); }
    public synchronized int count() { return questions().length(); }
    public long autoSupplyRevision() { return prefs.getLong("auto_supply_revision",0L); }
    public synchronized int wrongCount() { return reviews().length(); }

    /** 刷题采用最近应用的题库快照，不中途切换原来的锁定答题会话。 */
    public synchronized JSONObject practiceSnapshot() throws Exception {
        JSONArray roots=new JSONArray(prefs.getString(PENDING_ROOTS,prefs.getString(APPLIED_ROOTS,"[]"))),pool=new JSONArray();
        Set<String> ids=new HashSet<>();for(int i=0;i<roots.length();i++)ids.add(roots.getString(i));
        JSONArray all=questions();
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i));if(!ids.contains(root.rootId))continue;
            pool.put(root.toJson());for(Question variant:root.variants)pool.put(variant.toJson());
        }
        return new JSONObject().put("roots",roots).put("questions",pool);
    }

    public synchronized boolean practiceWasShown(Question question) throws Exception {
        JSONObject stat=stats().optJSONObject(question.id);
        return stat!=null&&stat.optInt("shown",0)>0||seenStems().has(stemKey(question.title));
    }

    public synchronized List<Question> installPractice(List<Question> incoming) throws Exception {
        installRecognized(incoming); List<Question> result=new ArrayList<>();JSONArray all=questions();
        for(Question generated:incoming) for(int i=0;i<all.length();i++) {
            Question stored=Question.fromStored(all.getJSONObject(i));
            if(stored.grade==generated.grade&&stored.sourceType.equals(generated.sourceType)&&stemKey(stored.title).equals(stemKey(generated.title))) {result.add(stored);break;}
        }
        return result;
    }

    /** 刷题仅写入展示和答题记录，不追加罚题；事件标记使重启重试不会重复计数。 */
    public synchronized void recordPractice(Question question,String round,int choice) throws Exception {
        JSONObject events=new JSONObject(prefs.getString("practice_events","{}"));String token=round+":"+question.id;
        JSONObject event=events.optJSONObject(token);if(event==null)event=new JSONObject();
        if(event.optBoolean("shown")&&(choice<0||event.optBoolean("answered")))return;
        JSONObject all=stats();initializeStats(all,question);JSONObject stat=all.getJSONObject(question.id);JSONObject seen=seenStems();
        if(!event.optBoolean("shown")) {
            stat.put("shown",stat.optInt("shown",0)+1).put("lastShown",System.currentTimeMillis()).put("historyKnown",true);
            seen.put(stemKey(question.title),true);event.put("shown",true);
        }
        if(choice>=0&&!event.optBoolean("answered")) {
            if(choice>=question.choices.size())throw new IllegalArgumentException("选项无效");
            String kind=choice==question.answerIndex?"correct":"wrong";
            stat.put("answers",stat.optInt("answers",0)+1).put(kind,stat.optInt(kind,0)+1);event.put("answered",true);
        }
        all.put(question.id,stat);events.put(token,event);
        if(!prefs.edit().putString(STATS,all.toString()).putString(SEEN_STEMS,seen.toString()).putString("practice_events",events.toString()).commit())throw new IllegalStateException("刷题记录保存失败");
        requestAutoSupply(false);
    }

    public synchronized void beginBlacklistSession(int fallbackLevel,String pkg,int count) throws Exception {
        JSONObject previous=readSession();
        // 有尚未完成的贷款时先保留原答题会话，不能切换应用抹掉欠题。
        if(previous!=null && previous.optInt("loanCount",0)>0 && !previous.optBoolean("completePending") && remaining(previous)>0) return;
        if(previous!=null && "blacklist".equals(previous.optString("mode"))
                && pkg.equals(previous.optString("package"))
                && previous.optJSONArray("items")!=null && (previous.optJSONArray("items").length()>0||previous.optInt("pendingNormal",0)>0)) return;
        activatePendingBank();
        JSONArray items=new JSONArray();
        JSONObject session=new JSONObject().put("mode","blacklist").put("package",pkg)
            .put("items",items).put("pendingNormal",Math.max(1,count)).put("hintCount",0).put("hint","");
        try { items.put(adaptiveItem(fallbackLevel,"")); session.put("pendingNormal",Math.max(0,count-1)); } catch(NoFreshQuestion waiting) { }
        setQuestionDeadline(session);
        saveSession(session);
    }

    public synchronized boolean isBlacklistSession() {
        JSONObject session=readSession(); return session!=null && "blacklist".equals(session.optString("mode"));
    }
    public synchronized String blacklistPackage() {
        JSONObject session=readSession(); return session==null?"":session.optString("package","");
    }

    public synchronized View current(int fallbackLevel) throws Exception {
        JSONObject session = ensureSession(fallbackLevel);
        if(removePreviouslyShownQueueItems(session)) { saveSession(session); session=ensureSession(fallbackLevel); }
        if(deferEarlyVariants(session)) { saveSession(session); session=ensureSession(fallbackLevel); }
        // 升级时保留已答错题目的暂停状态，重启后也不会继续倒计时。
        if(session.optInt("hintCount",0)>0 && !session.optBoolean("timerPaused")) {
            session.put("timerPaused",true); session.remove("animationUntil"); saveSession(session);
        }
        if(prioritizeQueuedBasics(session)) saveSession(session);
        if (!session.optBoolean("completePending") && expireCurrent(session, fallbackLevel)) session = ensureSession(fallbackLevel);
        JSONArray items = session.getJSONArray("items"); JSONObject first = items.getJSONObject(0);
        Question q = Question.fromStored(first.getJSONObject("question"));
        JSONArray triedArray = first.optJSONArray("tried"); Set<Integer> tried = new HashSet<>();
        if (triedArray != null) for (int i = 0; i < triedArray.length(); i++) tried.add(triedArray.optInt(i, -1));
        long deadlineAt = ensureQuestionDeadline(session);
        return new View(q, tried, remaining(session), session.optInt("hintCount", 0), session.optString("hint", ""), deadlineAt, session.optBoolean("timerPaused"),session.optBoolean("timedOutCurrent"),isUntimed(session));
    }

    public synchronized AnswerResult answer(int choice, int fallbackLevel) throws Exception {
        JSONObject session = ensureSession(fallbackLevel); JSONArray items = session.getJSONArray("items");
        if (session.optBoolean("completePending")) return new AnswerResult(true, true, "", remaining(session));
        if (expireCurrent(session, fallbackLevel)) return new AnswerResult(false, false, true, "", remaining(session));
        JSONObject first = items.getJSONObject(0); Question q = Question.fromStored(first.getJSONObject("question"));
        if (choice < 0 || choice >= q.choices.size()) throw new IllegalArgumentException("选项无效");
        if (choice == q.answerIndex) {
            recordResult(q,"correct");
            if(prioritizeQueuedBasics(session)) items=session.getJSONArray("items");
            if (items.length() == 1 && session.optInt("pendingNormal",0)==0) {
                session.put("completePending", true);
                if (first.optBoolean("review")) session.put("completedReviewRoot", q.rootId);
                saveSession(session);
                return new AnswerResult(true, true, "", 0);
            }
            if (first.optBoolean("review")) markReviewCorrect(q.rootId);
            items.remove(0); session.put("items", items).put("hintCount", 0).put("hint", "");
            if(items.length()==0 && session.optInt("pendingNormal",0)>0) {
                try { items.put(nextPending(session,fallbackLevel,q.rootId)); session.put("items",items).put("pendingNormal",session.optInt("pendingNormal")-1); }
                catch(NoFreshQuestion waiting) { /* 保留待答数量，等待补题。 */ }
            }
            session.remove("animationUntil");
            setQuestionDeadline(session);
            saveSession(session);
            return new AnswerResult(true, false, "", remaining(session));
        }

        JSONArray tried = first.optJSONArray("tried"); if (tried == null) tried = new JSONArray();
        if (!contains(tried, choice)) tried.put(choice);
        first.put("tried", tried); items.put(0, first);
        recordResult(q,"wrong"); recordWrong(q, fallbackLevel);
        String hint = q.hints.get(Math.min(session.optInt("hintCount", 0), q.hints.size() - 1));
        int hintCount = session.optInt("hintCount", 0) + 1;
        session.put("items", items).put("hintCount", hintCount).put("hint", hint);
        session.put("timerPaused",true); session.remove("animationUntil");
        Question penalty = nextVariant(q, fallbackLevel,items);
        if(penalty!=null) items.put(item(penalty, false)); else if(!"system".equals(q.sourceType)) addPendingReview(session,q.rootId);
        session.put("items", items); prioritizeQueuedBasics(session);
        saveSession(session);
        return new AnswerResult(false, false, hint, remaining(session));
    }

    public synchronized void finishSession() throws Exception {
        JSONObject session = readSession();
        if (session != null && session.optBoolean("completePending")) {
            String reviewRoot = session.optString("completedReviewRoot", "");
            if (!reviewRoot.isEmpty()) markReviewCorrect(reviewRoot);
        }
        prefs.edit().remove(SESSION).commit();
    }
    public synchronized void clearSession() { prefs.edit().remove(SESSION).commit(); }

    public synchronized AnswerResult checkTimeout(int fallbackLevel) throws Exception {
        JSONObject session = ensureSession(fallbackLevel);
        boolean timedOut = !session.optBoolean("completePending") && expireCurrent(session, fallbackLevel);
        return new AnswerResult(false, false, timedOut, "", remaining(session));
    }

    private boolean expireCurrent(JSONObject session, int fallbackLevel) throws Exception {
        JSONArray items=session.getJSONArray("items");
        if(session.optBoolean("timerPaused") || items.length()==0 || isUntimed(session) || System.currentTimeMillis()<ensureQuestionDeadline(session)) return false;
        JSONObject first=items.getJSONObject(0);
        Question timedOut=Question.fromStored(first.getJSONObject("question"));
        recordResult(timedOut,"timeout"); recordWrong(timedOut,fallbackLevel);
        // 保留当前题并暂停，只追加一道，避免重复触发超时。
        Question extra=nextVariant(timedOut,fallbackLevel,items);
        if(extra!=null) items.put(item(extra,false)); else if(!"system".equals(timedOut.sourceType)) addPendingReview(session,timedOut.rootId);
        session.put("items",items).put("timerPaused",true).put("timedOutCurrent",true); prioritizeQueuedBasics(session);
        session.remove("animationUntil"); saveSession(session);
        return true;
    }

    private Question randomQuestionExcept(Question excluded,int fallbackLevel) throws Exception {
        List<Question> candidates=new ArrayList<>(); JSONArray stored=questions();
        for(int i=0;i<stored.length();i++) {
            Question candidate=Question.fromStored(stored.getJSONObject(i));
            if(candidate.grade==excluded.grade && !candidate.rootId.equals(excluded.rootId)) candidates.add(candidate);
        }
        if(candidates.isEmpty()) return new Question(fallbackLevel,excluded.grade);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private Question randomQuestionOutsideQueue(JSONArray items,Question excluded,int fallbackLevel) throws Exception {
        Set<String> usedRoots=new HashSet<>(); usedRoots.add(excluded.rootId);
        for(int i=0;i<items.length();i++) {
            JSONObject value=items.getJSONObject(i).optJSONObject("question");
            if(value!=null) usedRoots.add(value.optString("rootId",value.optString("id")));
        }
        List<Question> candidates=new ArrayList<>(); JSONArray stored=questions();
        for(int i=0;i<stored.length();i++) {
            Question candidate=Question.fromStored(stored.getJSONObject(i));
            String active=activeChapter(); boolean basic=!active.isEmpty()&&(baseCount(active)<BASE_TARGET||baseAnswered(active)<BASE_TARGET);
            if(candidate.grade==excluded.grade && (active.isEmpty()||active.equals(candidate.chapterId)) && (!basic||candidate.questionType.equals("base")) && !usedRoots.contains(candidate.rootId)) candidates.add(candidate);
        }
        if(candidates.isEmpty()) return new Question(fallbackLevel,excluded.grade);
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private boolean hasQuestion(JSONArray items,String id) throws Exception {
        for(int i=0;i<items.length();i++) {
            JSONObject value=items.getJSONObject(i).optJSONObject("question");
            if(value!=null && id.equals(value.optString("id"))) return true;
        }
        return false;
    }

    /** 拓展题、专项题和错题变体不设倒计时。 */
    private boolean isUntimed(JSONObject session) throws Exception {
        JSONArray items=session.optJSONArray("items"); if(items==null||items.length()==0) return false;
        JSONObject first=items.getJSONObject(0),q=first.getJSONObject("question");
        String type=q.optString("questionType","base"),id=q.optString("id",""),root=q.optString("rootId",id);
        return "extension".equals(type)||"exam".equals(type)||first.optBoolean("review")||(!id.isEmpty()&&!root.equals(id));
    }

    private long ensureQuestionDeadline(JSONObject session) throws Exception {
        if(isUntimed(session)) return 0L;
        long deadline=session.optLong("deadlineAt",0L);
        if(deadline>0) return deadline;
        setQuestionDeadline(session); saveSession(session);
        return session.optLong("deadlineAt",0L);
    }

    private void setQuestionDeadline(JSONObject session) throws Exception {
        session.remove("timerPaused"); session.remove("timedOutCurrent"); session.remove("animationUntil");
        JSONArray items=session.optJSONArray("items");
        if(items==null || items.length()==0) { session.put("deadlineAt",0L); return; }
        if(isUntimed(session)) { session.put("deadlineAt",0L); return; }
        Question current=Question.fromStored(items.getJSONObject(0).getJSONObject("question"));
        long now=System.currentTimeMillis(), seconds=Math.max(1L,current.timeLimitSeconds);
        long room=(Long.MAX_VALUE-now)/1000L;
        session.put("deadlineAt",seconds>room?Long.MAX_VALUE:now+seconds*1000L);
    }

    private JSONObject ensureSession(int fallbackLevel) throws Exception {
        JSONObject current = readSession();
        if (current != null && current.optJSONArray("items") != null && current.optJSONArray("items").length() > 0) return current;
        if(current!=null && current.optInt("pendingNormal",0)>0) {
            JSONArray items=new JSONArray(); items.put(nextPending(current,fallbackLevel,""));
            current.put("items",items).put("pendingNormal",current.optInt("pendingNormal")-1); setQuestionDeadline(current); saveSession(current); return current;
        }
        activatePendingBank();
        JSONArray items = new JSONArray(); items.put(timeItem(fallbackLevel,""));
        // 新轮次固定题数，保存后不影响正在进行的答题会话。
        JSONObject session = new JSONObject().put("mode","time").put("items", items)
            .put("pendingNormal",GateState.get(context).activeRoundQuestions()-1).put("hintCount", 0).put("hint", "");
        setQuestionDeadline(session);
        saveSession(session); return session;
    }

    private int remaining(JSONObject session) { JSONArray items=session.optJSONArray("items"); return (items==null?0:items.length())+Math.max(0,session.optInt("pendingNormal",0)); }

    /** 贷款次数和欠题保存在同一答题会话中，避免重启后丢失。 */
    public synchronized int loanCount() {
        JSONObject session=readSession(); return session==null?0:session.optInt("loanCount",0);
    }
    public synchronized int borrowTime() throws Exception {
        JSONObject session=readSession();
        if(session==null) session=new JSONObject().put("mode","time").put("items",new JSONArray())
            .put("pendingNormal",1).put("hintCount",0).put("hint","");
        int[] minutes={2,3,5,10}, questions={1,3,10,0};
        int count=session.optInt("loanCount",0); long now=System.currentTimeMillis();
        if(count>=minutes.length || session.optBoolean("completePending") || session.optLong("loanUntil",0)>now) return 0;
        long deadline=session.optLong("deadlineAt",0);
        if(deadline>0 && !session.optBoolean("timerPaused")) {
            // 借用期间暂停本题倒计时，到期后恢复剩余秒数。
            session.put("loanQuestionRemaining",Math.max(1000L,deadline-now));
        }
        session.put("loanCount",count+1).put("loanUntil",now+minutes[count]*60_000L)
            .put("pendingNormal",Math.max(0,session.optInt("pendingNormal",0))+questions[count]);
        if(!prefs.edit().putString(SESSION,session.toString()).commit()) throw new IllegalStateException("贷款保存失败");
        return minutes[count];
    }
    public synchronized long loanRemainingMs() {
        JSONObject session=readSession(); if(session==null) return 0;
        long until=session.optLong("loanUntil",0); if(until<=0) return 0;
        long now=System.currentTimeMillis(); if(until>now) return until-now;
        try {
            if(session.has("loanQuestionRemaining")) {
                session.put("deadlineAt",now+Math.max(1000L,session.optLong("loanQuestionRemaining",1000L)));
                session.remove("loanQuestionRemaining");
            }
            session.remove("loanUntil");
            if(!prefs.edit().putString(SESSION,session.toString()).commit()) throw new IllegalStateException("贷款到期状态保存失败");
        } catch(Exception e) { android.util.Log.e("WatchGateQuiz","贷款到期状态保存失败",e); }
        return 0;
    }
    public synchronized boolean loanDebtDue() {
        JSONObject session=readSession();
        return session!=null && session.optInt("loanCount",0)>0 && session.optLong("loanUntil",0)<=System.currentTimeMillis()
            && !session.optBoolean("completePending") && remaining(session)>0;
    }


    /** 本轮每次尝试进入设置按约定递增加题，进度与次数一起持久化。 */
    public synchronized int addSettingsAttempt() throws Exception {
        JSONObject session=readSession();
        if(session==null) session=new JSONObject().put("mode","time").put("items",new JSONArray())
            .put("pendingNormal",1).put("hintCount",0).put("hint","");
        if(session.optBoolean("completePending")) return 0;
        int count=session.optInt("settings_attempt_count",0);
        int[] additions={1,3,5,9,16}; int amount=additions[Math.min(count,additions.length-1)];
        session.put("settings_attempt_count",count+1).put("pendingNormal",session.optInt("pendingNormal",0)+amount);
        if(!prefs.edit().putString(SESSION,session.toString()).commit()) throw new IllegalStateException("加题进度保存失败");
        return amount;
    }
    public synchronized int currentRemaining() {
        JSONObject session=readSession(); return session==null?0:remaining(session);
    }

    private void addPendingReview(JSONObject session,String rootId) throws Exception {
        JSONArray roots=session.optJSONArray("pendingReviewRoots"); if(roots==null) roots=new JSONArray();
        roots.put(rootId); session.put("pendingReviewRoots",roots).put("pendingNormal",session.optInt("pendingNormal",0)+1);
    }

    private JSONObject nextPending(JSONObject session,int fallbackLevel,String excludedRoot) throws Exception {
        JSONArray roots=session.optJSONArray("pendingReviewRoots"); String chapter=activeChapter();
        boolean basic=!chapter.isEmpty()&&(baseCount(chapter)<BASE_TARGET||baseAnswered(chapter)<BASE_TARGET);
        JSONObject next;
        if(roots!=null&&roots.length()>0&&!basic) {
            String rootId=roots.getString(0); JSONObject raw=findRoot(rootId); Question chosen=null;
            if(raw!=null) { Question root=Question.fromStored(raw); for(Question variant:root.variants) if(isFresh(variant)&&!queuedInSession(variant)) { chosen=variant; break; } }
            if(chosen==null) { requestReviewVariants(rootId); next=pendingItem(session,fallbackLevel,excludedRoot); }
            else next=item(chosen,false);
        } else next=pendingItem(session,fallbackLevel,excludedRoot);
        if(roots!=null&&roots.length()>0) { roots.remove(0); session.put("pendingReviewRoots",roots); }
        return next;
    }

    private static String stemKey(String value) { return MathText.format(value).replaceAll("\\s+","").toLowerCase(java.util.Locale.ROOT); }

    /** 兼容旧版本答题记录，并按题干去重，避免不同 ID 的同题重复。 */
    private JSONObject seenStems() throws Exception {
        JSONObject seen=new JSONObject(prefs.getString(SEEN_STEMS,"{}")),allStats=stats(); JSONArray all=questions();
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i)); List<Question> group=new ArrayList<>(); group.add(root); group.addAll(root.variants);
            for(Question q:group) { JSONObject s=allStats.optJSONObject(q.id); if(s!=null&&s.optInt("shown",0)>0) seen.put(stemKey(q.title),true); }
        }
        return seen;
    }

    private boolean isFresh(Question question) throws Exception {
        JSONObject s=stats().optJSONObject(question.id);
        if("system".equals(question.sourceType)) {
            return (s==null||s.optInt("shown",0)==0)&&!seenStems().has(stemKey(question.title));
        }
        if(!belongsToAppliedBank(question)) return false;
        if(s==null||!s.optBoolean("historyKnown",false)||s.optInt("shown",0)>0) return false;
        return !seenStems().has(stemKey(question.title));
    }

    private boolean queueContains(JSONArray queue,Question question) throws Exception {
        if(queue==null) return false;
        for(int i=0;i<queue.length();i++) { JSONObject q=queue.getJSONObject(i).optJSONObject("question");
            if(q!=null&&(question.id.equals(q.optString("id"))||stemKey(question.title).equals(stemKey(q.optString("stem",""))))) return true;
        }
        return false;
    }

    private boolean queuedInSession(Question question) throws Exception {
        JSONObject session=readSession(); return session!=null&&queueContains(session.optJSONArray("items"),question);
    }

    /** 升级后保留正在作答的题，把旧队列中的重复题转成待补数量。 */
    private boolean removePreviouslyShownQueueItems(JSONObject session) throws Exception {
        JSONArray items=session.optJSONArray("items"); if(items==null||items.length()==0) return false;
        JSONArray kept=new JSONArray(),roots=session.optJSONArray("pendingReviewRoots"); if(roots==null) roots=new JSONArray();
        Set<String> keys=new HashSet<>(); int removed=0; boolean firstRemoved=false;
        for(int i=0;i<items.length();i++) {
            JSONObject entry=items.getJSONObject(i); Question q=Question.fromStored(entry.getJSONObject("question")); String key=stemKey(q.title);
            if((i==0&&entry.optBoolean("shownRecorded"))||(isFresh(q)&&!keys.contains(key))) { kept.put(entry); keys.add(key); }
            else { removed++; if(i==0) firstRemoved=true; if(!q.id.equals(q.rootId)||entry.optBoolean("review")) roots.put(q.rootId); }
        }
        if(removed==0) return false;
        session.put("items",kept).put("pendingNormal",session.optInt("pendingNormal",0)+removed).put("pendingReviewRoots",roots);
        if(firstRemoved) { session.put("hintCount",0).put("hint",""); setQuestionDeadline(session); }
        return true;
    }

    /** 旧队列中的变体不抢在基础题之前出现，当前正在答的题保持不变。 */
    private boolean deferEarlyVariants(JSONObject session) throws Exception {
        String chapter=activeChapter(); JSONArray items=session.optJSONArray("items");
        if(chapter.isEmpty()||items==null||baseAnswered(chapter)>=BASE_TARGET) return false;
        JSONArray kept=new JSONArray(); boolean changed=false,firstRemoved=false;
        for(int i=0;i<items.length();i++) {
            JSONObject entry=items.getJSONObject(i),raw=entry.getJSONObject("question");
            String id=raw.optString("id"),root=raw.optString("rootId",id);
            boolean variant=!id.equals(root)||entry.optBoolean("review");
            if(variant&&!entry.optBoolean("basicSubstitute")&&!(i==0&&entry.optBoolean("shownRecorded"))) {
                addPendingReview(session,root); changed=true; if(i==0) firstRemoved=true;
            } else kept.put(entry);
        }
        if(changed) { session.put("items",kept); if(firstRemoved) { session.put("hintCount",0).put("hint",""); setQuestionDeadline(session); } }
        return changed;
    }

    public boolean preparingQuestions() { return basicGenerationRunning.get()||reviewGenerationRunning.get()||expansionRunning.get(); }
    public String waitingStatus() {
        if(!ApiKeyVault.isConfigured(context)) return "请家长在儿童手机上设置百炼 API Key";
        if(autoSupplyRunning.get()&&!autoSupplyStatus.isEmpty()) return autoSupplyStatus;
        if(basicGenerationRunning.get()) return basicStatus;
        if(reviewGenerationRunning.get()) return reviewStatus;
        if(expansionRunning.get()) return expansionStatus;
        if(autoSupplyStatus.contains("失败")) return autoSupplyStatus;
        if(basicStatus.contains("失败")) return basicStatus;
        if(reviewStatus.contains("失败")) return reviewStatus;
        if(expansionStatus.contains("失败")) return expansionStatus;
        return "暂无可用的新题，请家长检查题库、网络或百炼额度";
    }
    public static final class NoFreshQuestion extends Exception { NoFreshQuestion() { super("没有可用的新题"); } }

    // 普通立即弹题无可用题时，才从未出现过的系统内置题补充。
    private JSONObject timeItem(int fallbackLevel,String excludedRoot) throws Exception {
        try { return adaptiveItem(fallbackLevel,excludedRoot); }
        catch(NoFreshQuestion waiting) {
            Question builtin=nextBuiltinQuestion(excludedRoot);
            if(builtin!=null&&!queuedInSession(builtin)) return item(builtin,false);
            throw waiting;
        }
    }
    private JSONObject pendingItem(JSONObject session,int fallbackLevel,String excludedRoot) throws Exception {
        return "blacklist".equals(session.optString("mode"))?adaptiveItem(fallbackLevel,excludedRoot):timeItem(fallbackLevel,excludedRoot);
    }

    /** 基础阶段优先未作答原题；完成后错题和拓展题同权，少量基础巩固。 */
    private JSONObject adaptiveItem(int fallbackLevel,String excludedRoot) throws Exception {
        // 新装设备还没有任何生成题时，普通答题锁定直接使用内置小学题。
        if(questions().length()==0) {
            Question builtin=nextBuiltinQuestion(excludedRoot);
            if(builtin!=null)return item(builtin,false);
        }
        // 显式应用题库后，禁用跨章节兜底；只从应用的原题及其错题变体中选题。
        if(prefs.contains(APPLIED_ROOTS)) {
            JSONArray all=questions(); List<Question> selected=new ArrayList<>();Question sameRootPractice=null;
            for(int i=0;i<all.length();i++) {
                Question root=Question.fromStored(all.getJSONObject(i));
                if(!belongsToAppliedBank(root)) continue;
                selected.add(root);
                if(!root.rootId.equals(excludedRoot)&&isFresh(root)&&!queuedInSession(root)) return item(root,false);
            }
            // 先用不同原题下的预备变体；单题库时允许同一原题的不同新变体连续出题。
            for(Question root:selected) for(Question variant:root.variants) {
                if(!"practice".equals(variant.questionType)||!isFresh(variant)||queuedInSession(variant)) continue;
                if(!root.rootId.equals(excludedRoot)) return item(variant,false);
                if(sameRootPractice==null) sameRootPractice=variant;
            }
            for(Question root:selected) if(findReview(root.rootId)!=null) {
                for(Question variant:root.variants) if(!"practice".equals(variant.questionType)&&isFresh(variant)&&!queuedInSession(variant)) return item(variant,true);
            }
            if(sameRootPractice!=null) return item(sameRootPractice,false);
            for(Question root:selected) if(findReview(root.rootId)!=null) requestReviewVariants(root.rootId);
            throw new NoFreshQuestion();
        }
        Question exam=nextExamQuestion(excludedRoot); if(exam!=null) return item(exam,false);
        String chapter=activeChapter();
        boolean basic=!chapter.isEmpty()&&(baseCount(chapter)<BASE_TARGET||baseAnswered(chapter)<BASE_TARGET);
        if(!basic) { Question examReview=dueReviewQuestion(excludedRoot,true); if(examReview!=null) return item(examReview,true); }
        if(chapter.isEmpty()) {
            Question due=dueReviewQuestion(excludedRoot); if(due!=null) return item(due,true);
            Question fresh=anyFreshQuestion(excludedRoot); if(fresh!=null) return item(fresh,false);
            Question variant=anyFreshReviewVariant(excludedRoot); if(variant!=null) return item(variant,false);
            throw new NoFreshQuestion();
        }
        if(basic) {
            if(baseCount(chapter)<BASE_TARGET) requestMissingBasics(chapter,activeGrade());
            Question q=pickChapterQuestion(chapter,"base",true,false,excludedRoot);
            if(q!=null) return item(q,false);
            throw new NoFreshQuestion();
        }
        maybeGenerateExpansion(); int draw=ThreadLocalRandom.current().nextInt(100);
        if(draw<10) {
            Question q=pickChapterQuestion(chapter,"base",false,true,excludedRoot); if(q!=null) return item(q,false);
        }
        if(draw>=10 && draw<55) {
            Question q=dueReviewQuestion(excludedRoot); if(q!=null) return item(q,true);
        }
        Question extension=pickChapterQuestion(chapter,"extension",true,false,excludedRoot);
        if(extension==null) extension=pickChapterQuestion(chapter,"extension",false,false,excludedRoot);
        if(extension!=null) return item(extension,false);
        Question due=dueReviewQuestion(excludedRoot); if(due!=null) return item(due,true);
        Question freshVariant=anyFreshReviewVariant(excludedRoot); if(freshVariant!=null) return item(freshVariant,false);
        Question base=pickChapterQuestion(chapter,"base",false,false,excludedRoot);
        if(base!=null) return item(base,false);
        throw new NoFreshQuestion();
    }

    private Question nextBuiltinQuestion(String excludedRoot) throws Exception {
        int grade=activeGrade();
        if(grade<1||grade>6)return null;
        JSONArray source=BuiltinPracticeQuestions.load(context);List<Question> fresh=new ArrayList<>();
        for(int i=0;i<source.length();i++) {
            Question question=Question.fromStored(source.getJSONObject(i));
            if(question.grade!=grade)continue;
            question.questionType="base";
            if(!question.rootId.equals(excludedRoot)&&isFresh(question))fresh.add(question);
        }
        if(fresh.isEmpty())return null;
        return fresh.get(ThreadLocalRandom.current().nextInt(fresh.size()));
    }

    /** 基础阶段把追加机会先用于未做基础题，错题仍保留在复习记录。 */
    private boolean prioritizeQueuedBasics(JSONObject session) throws Exception {
        String chapter=activeChapter(); JSONArray queued=session.optJSONArray("items");
        if(chapter.isEmpty()||queued==null||queued.length()<2||baseAnswered(chapter)>=BASE_TARGET) return false;
        JSONArray all=questions(); JSONObject allStats=stats(); Set<String> already=new HashSet<>();
        for(int i=0;i<queued.length();i++) { JSONObject q=queued.getJSONObject(i).optJSONObject("question"); if(q!=null) already.add(q.optString("rootId",q.optString("id"))); }
        List<Question> available=new ArrayList<>(); int baseIndex=0;
        for(int i=0;i<all.length();i++) {
            Question q=Question.fromStored(all.getJSONObject(i));
            if(!chapter.equals(q.chapterId)||!"base".equals(q.questionType)||!q.id.equals(q.rootId)) continue;
            if(baseIndex++>=BASE_TARGET) break;
            JSONObject stat=allStats.optJSONObject(q.id);
            if((stat==null||stat.optInt("answers",0)==0)&&!already.contains(q.rootId)&&isFresh(q)) available.add(q);
        }
        List<JSONObject> basics=new ArrayList<>(),others=new ArrayList<>(); boolean changed=false; int next=0;
        for(int i=1;i<queued.length();i++) {
            JSONObject entry=queued.getJSONObject(i),raw=entry.getJSONObject("question");
            String id=raw.optString("id"),root=raw.optString("rootId",id),type=raw.optString("questionType","base");
            JSONObject stat=allStats.optJSONObject(id);
            boolean unseenBasic=chapter.equals(raw.optString("chapterId"))&&"base".equals(type)&&id.equals(root)&&(stat==null||stat.optInt("answers",0)==0);
            boolean examOriginal="exam".equals(type)&&id.equals(root)&&!entry.optBoolean("review");
            if(!unseenBasic&&!examOriginal&&next<available.size()) { entry=item(available.get(next++),false); unseenBasic=true; changed=true; }
            if(unseenBasic) basics.add(entry); else others.add(entry);
        }
        JSONArray arranged=new JSONArray(); arranged.put(queued.getJSONObject(0));
        // 已安排的基础题提前，但不抢占明确优先的试卷专项原题。
        for(JSONObject entry:others) { JSONObject raw=entry.getJSONObject("question"); if("exam".equals(raw.optString("questionType"))&&raw.optString("id").equals(raw.optString("rootId"))&&!entry.optBoolean("review")) arranged.put(entry); }
        for(JSONObject entry:basics) arranged.put(entry);
        for(JSONObject entry:others) { JSONObject raw=entry.getJSONObject("question"); if(!("exam".equals(raw.optString("questionType"))&&raw.optString("id").equals(raw.optString("rootId"))&&!entry.optBoolean("review"))) arranged.put(entry); }
        if(!changed && !arranged.toString().equals(queued.toString())) changed=true;
        if(changed) session.put("items",arranged); return changed;
    }

    /** 没有出现过的试卷专项题先出，避免重复加入当前答题队列。 */
    private Question nextExamQuestion(String excludedRoot) throws Exception {
        JSONArray all=questions(); JSONObject allStats=stats(); JSONObject session=readSession();
        JSONArray queued=session==null?null:session.optJSONArray("items");
        for(int i=0;i<all.length();i++) {
            JSONObject raw=all.getJSONObject(i);
            if(!"exam".equals(raw.optString("questionType"))) continue;
            Question q=Question.fromStored(raw); JSONObject stat=allStats.optJSONObject(q.id);
            if(q.rootId.equals(excludedRoot)||!isFresh(q)) continue;
            if(queued!=null&&queueContains(queued,q)) continue;
            return q;
        }
        return null;
    }

    private Question pickChapterQuestion(String chapter,String type,boolean unansweredOnly,boolean correctOnly,String excludedRoot) throws Exception {
        JSONArray all=questions(); JSONObject allStats=stats(); List<Question> best=new ArrayList<>(); int lowest=Integer.MAX_VALUE,baseIndex=0; long oldest=Long.MAX_VALUE;
        for(int i=0;i<all.length();i++) {
            Question q=Question.fromStored(all.getJSONObject(i));
            if(!chapter.equals(q.chapterId)||!type.equals(q.questionType)) continue;
            if(type.equals("base")&&unansweredOnly&&baseIndex++>=BASE_TARGET) continue;
            if(q.rootId.equals(excludedRoot)||!isFresh(q)||queuedInSession(q)) continue;
            JSONObject s=allStats.optJSONObject(q.id); int answers=s==null?0:s.optInt("answers",0),shown=s==null?0:s.optInt("shown",0),correct=s==null?0:s.optInt("correct",0);
            if(unansweredOnly&&answers>0 || correctOnly&&correct==0) continue;
            long last=s==null?0:s.optLong("lastShown",0);
            if(shown<lowest||(shown==lowest&&last<oldest)) { best.clear(); lowest=shown; oldest=last; }
            if(shown==lowest&&last==oldest) best.add(q);
        }
        return best.isEmpty()?null:best.get(ThreadLocalRandom.current().nextInt(best.size()));
    }

    private Question anyFreshQuestion(String excludedRoot) throws Exception {
        JSONArray all=questions();
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i));
            if(root.grade!=activeGrade()||root.rootId.equals(excludedRoot)) continue;
            if(isFresh(root)&&!queuedInSession(root)) return root;
        }
        return null;
    }

    /** 基础原题已出现但未作答时，用同考点未出现过的变体继续基础阶段。 */
    private Question unansweredBasicVariant(String chapter,String excludedRoot) throws Exception {
        JSONArray all=questions(); JSONObject allStats=stats(); int considered=0;
        for(int i=0;i<all.length();i++) {
            Question root=Question.fromStored(all.getJSONObject(i));
            if(!chapter.equals(root.chapterId)||!"base".equals(root.questionType)||!root.id.equals(root.rootId)) continue;
            if(considered++>=BASE_TARGET) break;
            if(root.rootId.equals(excludedRoot)||queuedInSession(root)) continue;
            JSONObject original=allStats.optJSONObject(root.id); boolean answered=original!=null&&original.optInt("answers",0)>0;
            for(Question variant:root.variants) { JSONObject s=allStats.optJSONObject(variant.id); if(s!=null&&s.optInt("answers",0)>0) { answered=true; break; } }
            if(answered) continue;
            for(Question variant:root.variants) if(isFresh(variant)&&!queuedInSession(variant)) return variant;
            requestReviewVariants(root.rootId);
        }
        return null;
    }

    /** 库存不足时允许提前复习未出现过的错题变体，原来的复习排期保留。 */
    private Question anyFreshReviewVariant(String excludedRoot) throws Exception {
        JSONArray reviews=reviews(); int grade=activeGrade(); String chapter=activeChapter();
        for(int i=0;i<reviews.length();i++) {
            String rootId=reviews.getJSONObject(i).optString("rootId",""); if(rootId.equals(excludedRoot)) continue;
            JSONObject raw=findRoot(rootId); if(raw==null) continue; Question root=Question.fromStored(raw);
            if(root.grade!=grade||(!chapter.isEmpty()&&!chapter.equals(root.chapterId)&&!"exam".equals(root.questionType))) continue;
            for(Question variant:root.variants) if(isFresh(variant)&&!queuedInSession(variant)) return variant;
            requestReviewVariants(rootId);
        }
        return null;
    }

    private List<Question> questionPool(int fallbackLevel) throws Exception {
        List<Question> pool=new ArrayList<>(); JSONArray stored=questions(); int grade=activeGrade();
        for(int i=0;i<stored.length();i++) {
            Question root=Question.fromStored(stored.getJSONObject(i));
            if(root.grade==grade) { pool.add(root); pool.addAll(root.variants); }
        }
        if(pool.isEmpty()) for(int i=0;i<50;i++) pool.add(new Question(fallbackLevel,grade));
        return pool;
    }

    private Question randomQuestion(int fallbackLevel) throws Exception {
        JSONArray stored = questions(); List<Question> matching=new ArrayList<>(); int grade=activeGrade();
        for(int i=0;i<stored.length();i++) {
            Question q=Question.fromStored(stored.getJSONObject(i)); if(q.grade==grade) matching.add(q);
        }
        if(matching.isEmpty()) return new Question(fallbackLevel,grade);
        return matching.get(ThreadLocalRandom.current().nextInt(matching.size()));
    }

    private Question nextVariant(Question current, int fallbackLevel,JSONArray queued) throws Exception {
        if("system".equals(current.sourceType))return null;
        String chapter=activeChapter();
        if(!chapter.isEmpty()&&(baseCount(chapter)<BASE_TARGET||baseAnswered(chapter)<BASE_TARGET)) return null;
        JSONObject review = findReview(current.rootId);
        JSONObject rootJson = findRoot(current.rootId);
        if (rootJson != null) {
            Question root = Question.fromStored(rootJson);
            if (!root.variants.isEmpty()) {
                int cursor = review == null ? 0 : review.optInt("variantCursor", 0);
                for(int offset=0;offset<root.variants.size();offset++) {
                    Question variant=root.variants.get((cursor+offset)%root.variants.size());
                    if(variant.id.equals(current.id)||queueContains(queued,variant)||!isFresh(variant)) continue;
                    if(review!=null) { review.put("variantCursor",cursor+offset+1); updateReview(review); }
                    return variant;
                }
            }
            requestReviewVariants(current.rootId);
        }
        // 暂无新变体时保留待答次数，等待千问补题。
        return null;
    }

    private Question leastShownOutsideQueue(JSONArray queued,Question current,int fallbackLevel) throws Exception {
        JSONArray all=questions(); JSONObject allStats=stats(); Question best=null; int least=Integer.MAX_VALUE; long oldest=Long.MAX_VALUE;
        for(int i=0;i<all.length();i++) {
            Question q=Question.fromStored(all.getJSONObject(i));
            if(q.grade!=current.grade||q.rootId.equals(current.rootId)||queueContains(queued,q)||!isFresh(q)) continue;
            String chapter=activeChapter(); if(!chapter.isEmpty()&&!chapter.equals(q.chapterId)) continue;
            JSONObject stat=allStats.optJSONObject(q.id); int shown=stat==null?0:stat.optInt("shown",0); long last=stat==null?0:stat.optLong("lastShown",0);
            if(shown<least||(shown==least&&last<oldest)) { best=q; least=shown; oldest=last; }
        }
        return best;
    }

    private Question sameSubjectQuestion(Question current, int fallbackLevel) throws Exception {
        JSONArray stored = questions();
        for (int i = 0; i < stored.length(); i++) {
            Question candidate = Question.fromStored(stored.getJSONObject(i));
            if (candidate.grade==current.grade && candidate.subject.equals(current.subject) && !candidate.rootId.equals(current.rootId)) return candidate;
        }
        return new Question(fallbackLevel,current.grade);
    }

    private void recordWrong(Question q, int fallbackLevel) throws Exception {
        if("system".equals(q.sourceType))return;
        JSONObject root = findRoot(q.rootId);
        if (root == null) {
            JSONArray stored = questions(); stored.put(q.toJson()); prefs.edit().putString(QUESTIONS, stored.toString()).commit();
        }
        JSONArray all = reviews(); JSONObject record = findReview(q.rootId);
        if (record == null) {
            record = new JSONObject().put("rootId", q.rootId).put("stage", 0).put("variantCursor", 0);
            all.put(record);
        }
        record.put("stage", 0).put("dueAt", System.currentTimeMillis() + INTERVALS[0]).put("fallbackLevel", fallbackLevel);
        saveReviews(all);
        requestReviewVariants(q.rootId);
    }

    private Question dueReviewQuestion(String excludedRoot) throws Exception {
        return dueReviewQuestion(excludedRoot,false);
    }

    private Question dueReviewQuestion(String excludedRoot,boolean examOnly) throws Exception {
        JSONArray all = reviews(); long now = System.currentTimeMillis(); int grade=activeGrade();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.getJSONObject(i);
            if (item.optLong("dueAt", Long.MAX_VALUE) > now) continue;
            if(excludedRoot.equals(item.optString("rootId"))) continue;
            JSONObject candidate=findRoot(item.optString("rootId"));
            if(candidate==null || (!examOnly&&candidate.optInt("grade",6)!=grade)) continue;
            boolean exam="exam".equals(candidate.optString("questionType"));
            if(examOnly&&!exam) continue;
            if(!examOnly && !activeChapter().isEmpty()&&!activeChapter().equals(candidate.optString("chapterId",""))) continue;
            Question root=Question.fromStored(candidate); int cursor=item.optInt("variantCursor",0);
            for(int offset=0;offset<root.variants.size();offset++) {
                Question variant=root.variants.get((cursor+offset)%root.variants.size());
                if(!isFresh(variant)||queuedInSession(variant)) continue;
                item.put("variantCursor",cursor+offset+1); saveReviews(all); return variant;
            }
            requestReviewVariants(root.rootId);
        }
        return null;
    }

    private void markReviewCorrect(String rootId) throws Exception {
        JSONObject record = findReview(rootId); if (record == null) return;
        int stage = Math.min(INTERVALS.length - 1, record.optInt("stage", 0) + 1);
        record.put("stage", stage).put("dueAt", System.currentTimeMillis() + INTERVALS[stage]);
        updateReview(record);
    }

    private JSONObject item(Question q, boolean review) throws Exception {
        return new JSONObject().put("question", q.toJson()).put("review", review).put("tried", new JSONArray());
    }

    private JSONObject findRoot(String rootId) throws Exception {
        JSONArray all = questions();
        for (int i = 0; i < all.length(); i++) {
            JSONObject q = all.getJSONObject(i); if (q.optString("rootId").equals(rootId)) return q;
        }
        return null;
    }

    private JSONObject findReview(String rootId) throws Exception {
        JSONArray all = reviews();
        for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.getJSONObject(i); if (item.optString("rootId").equals(rootId)) return item;
        }
        return null;
    }

    private void updateReview(JSONObject record) throws Exception {
        JSONArray all = reviews();
        for (int i = 0; i < all.length(); i++) if (all.getJSONObject(i).optString("rootId").equals(record.optString("rootId"))) {
            all.put(i, record); saveReviews(all); return;
        }
    }

    private void saveSession(JSONObject value) { prefs.edit().putString(SESSION, value.toString()).commit(); }
    private void saveReviews(JSONArray value) { prefs.edit().putString(REVIEWS, value.toString()).commit(); }
    private JSONObject readSession() {
        try { String value = prefs.getString(SESSION, ""); return value.isEmpty() ? null : new JSONObject(value); }
        catch (JSONException e) { return null; }
    }
    private JSONArray questions() {
        try { return new JSONArray(prefs.getString(QUESTIONS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }
    private JSONObject stats() {
        try { return new JSONObject(prefs.getString(STATS,"{}")); } catch(JSONException e) { return new JSONObject(); }
    }
    private JSONArray reviews() {
        try { return new JSONArray(prefs.getString(REVIEWS, "[]")); } catch (JSONException e) { return new JSONArray(); }
    }
    private static boolean contains(JSONArray values, int target) {
        for (int i = 0; i < values.length(); i++) if (values.optInt(i, -1) == target) return true;
        return false;
    }
    private static String signature(String chapterId,int grade, String subject, String topic, String stem) {
        return chapterId+"|"+subject.trim().toLowerCase(java.util.Locale.ROOT)
            + "|" + stem.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT) + "|" + grade;
    }

    public static final class View {
        public final Question question; public final Set<Integer> tried; public final int remaining, hintCount; public final String hint;
        public final long deadlineAtMillis; public final boolean timerPaused,timedOut,untimed;
        View(Question question, Set<Integer> tried, int remaining, int hintCount, String hint, long deadlineAtMillis, boolean timerPaused,boolean timedOut,boolean untimed) {
            this.question = question; this.tried = tried; this.remaining = remaining; this.hintCount = hintCount; this.hint = hint;
            this.deadlineAtMillis = deadlineAtMillis; this.timerPaused=timerPaused; this.timedOut=timedOut; this.untimed=untimed;
        }
    }
    public static final class AnswerResult {
        public final boolean correct, complete, timedOut; public final String hint; public final int remaining;
        AnswerResult(boolean correct, boolean complete, String hint, int remaining) {
            this(correct, complete, false, hint, remaining);
        }
        AnswerResult(boolean correct, boolean complete, boolean timedOut, String hint, int remaining) {
            this.correct = correct; this.complete = complete; this.timedOut = timedOut; this.hint = hint; this.remaining = remaining;
        }
    }
}
