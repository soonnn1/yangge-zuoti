package cn.tan.watchgate;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** 持久保存疑问题草稿及家长的处理结果，刷新页面后仍能继续核对。 */
final class ImportReviews {
    private final Context context;
    private final SharedPreferences operations, exams;
    private final java.util.concurrent.atomic.AtomicBoolean aiProcessing=new java.util.concurrent.atomic.AtomicBoolean(false);

    ImportReviews(Context context) {
        this.context=context.getApplicationContext();
        Context storage=context.createDeviceProtectedStorageContext();
        operations=storage.getSharedPreferences("watch_gate_operations",Context.MODE_PRIVATE);
        exams=storage.getSharedPreferences("watch_gate_exam_v1",Context.MODE_PRIVATE);
        // 应用重启后允许重试中断的任务，避免永久显示“补全中”。
        try {
            JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]")); boolean changed=false;
            for(int i=0;i<queue.length();i++) {
                JSONObject item=queue.getJSONObject(i);
                if("pending".equals(item.optString("state"))&&"running".equals(item.optString("aiState"))) {
                    item.put("aiState","failed").put("aiMessage","上次AI补全中断，请重试"); changed=true;
                }
            }
            if(changed) operations.edit().putString("importReviewQueue",queue.toString()).commit();
        } catch(Exception ignored) { }
    }

    synchronized void enqueue(JSONArray issues) throws Exception {
        JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]"));
        Set<String> known=new HashSet<>();
        for(int i=0;i<queue.length();i++) known.add(queue.getJSONObject(i).optString("id"));
        for(int i=0;i<issues.length();i++) {
            JSONObject issue=issues.getJSONObject(i);
            if(issue.optString("id").isEmpty()) issue.put("id",UUID.randomUUID().toString());
            if(known.add(issue.getString("id"))) queue.put(new JSONObject(issue.toString()).put("state","pending"));
        }
        if(!operations.edit().putString("importReviewQueue",queue.toString()).commit()) throw new IllegalStateException("疑问题保存失败");
    }

    /** 旧版本仅保留题干的疑问题也能补全；不猜测缺失的选项和正确答案。 */
    private void migrateLegacy() throws Exception {
        if(operations.getBoolean("importReviewMigrationV1",false)) return;
        JSONObject report=new JSONObject(exams.getString("report","{}"));
        JSONArray imageIssues=report.optJSONArray("importIssues");
        if(imageIssues!=null) {
            JSONArray rows=report.optJSONArray("questions");
            for(int i=0;i<imageIssues.length();i++) {
                JSONObject issue=imageIssues.getJSONObject(i),draft=new JSONObject();
                int index=issue.optInt("number",1)-1;
                if(rows!=null&&index>=0&&index<rows.length()) draft=new JSONObject(rows.getJSONObject(index).toString());
                legacyContext(issue,draft,"image",report.optInt("grade",0));
            }
            enqueue(imageIssues);
            if(!exams.edit().putString("report",report.toString()).commit()) throw new IllegalStateException("旧疑问题保存失败");
        }
        JSONArray documentIssues=new JSONArray(operations.getString("documentIssues","[]"));
        for(int i=0;i<documentIssues.length();i++) legacyContext(documentIssues.getJSONObject(i),new JSONObject(),"document",0);
        enqueue(documentIssues);
        if(!operations.edit().putString("documentIssues",documentIssues.toString()).putBoolean("importReviewMigrationV1",true).commit()) throw new IllegalStateException("疑问题迁移失败");
    }

    private void legacyContext(JSONObject issue,JSONObject draft,String source,int grade) throws Exception {
        if(issue.has("draft")) return;
        draft.put("stem",issue.optString("stem",draft.optString("stem"))).put("grade",grade);
        issue.put("draft",draft).put("sourceType",source).put("chapterId",source.equals("image")?"__exam__":"__document__")
            .put("chapterTitle",source.equals("image")?"图片导入":"文档导入").put("term","");
    }

    synchronized JSONObject pending() throws Exception {
        migrateLegacy();
        JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]")),pending=new JSONArray();
        for(int i=0;i<queue.length();i++) if("pending".equals(queue.getJSONObject(i).optString("state"))) pending.put(queue.getJSONObject(i));
        return new JSONObject().put("questions",pending);
    }

    synchronized JSONObject startAi(JSONObject input,java.util.concurrent.Executor executor) throws Exception {
        migrateLegacy();
        if(!ApiKeyVault.isConfigured(context)) throw new IllegalArgumentException("请先配置百炼 API Key");
        final String id=input.getString("id");
        JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]")); JSONObject issue=null;
        for(int i=0;i<queue.length();i++) if(id.equals(queue.getJSONObject(i).optString("id"))) { issue=queue.getJSONObject(i); break; }
        if(issue==null) throw new IllegalArgumentException("待确认题目已不存在，请刷新");
        if(!"pending".equals(issue.optString("state"))) return new JSONObject().put("alreadyHandled",true);
        if("running".equals(issue.optString("aiState"))) return new JSONObject().put("started",false);
        final JSONObject draft=new JSONObject(input.getJSONObject("question").toString());
        if(draft.optString("stem").trim().isEmpty()) throw new IllegalArgumentException("缺少题干，请先补充原题内容");
        if(draft.toString().length()>20000||draft.optInt("grade",0)<1||draft.optInt("grade",0)>12) throw new IllegalArgumentException("请检查题目长度和年级");
        JSONObject draftChapter=TextbookKnowledge.chapter(context,draft.optString("chapterId"));
        if(draftChapter==null||draftChapter.optInt("grade")!=draft.optInt("grade"))throw new IllegalArgumentException("请选择所属教材章节");
        if(!aiProcessing.compareAndSet(false,true)) throw new IllegalArgumentException("另一道题正在AI补全，请稍候");
        final String reason=issue.optString("reason");
        try {
            issue.put("draft",draft).put("aiState","running").put("aiMessage","正在AI补全题目…");
            if(!operations.edit().putString("importReviewQueue",queue.toString()).commit()) throw new IllegalStateException("补全任务保存失败");
            executor.execute(() -> {
                try {
                    String apiKey=ApiKeyVault.read(context);
                    JSONObject completed=QwenClient.completeImportedQuestion(apiKey,draft,reason);
                    if(!completed.optBoolean("canComplete",false)) {
                        String message=completed.optString("reason","").trim();
                        throw new IllegalStateException(message.isEmpty()?"无法可靠补全，请手动修改":message);
                    }
                    completed.put("grade",draft.getInt("grade")).put("chapterId",draft.getString("chapterId"));
                    if(completed.optString("subject").isEmpty()) completed.put("subject",draft.optString("subject"));
                    if(completed.optJSONArray("options")==null||completed.getJSONArray("options").length()!=4) throw new IllegalStateException("AI补全的选项不完整，请重试或手动修改");
                    if(!saveAiState(id,"running","补全完成，正在独立核对答案…",completed)) return;
                    JSONObject candidate=new JSONObject().put("id",id).put("subject",completed.optString("subject"))
                        .put("stem",completed.getString("stem")).put("options",completed.getJSONArray("options"));
                    JSONArray checks=QwenClient.verifyImportedQuestions(apiKey,new JSONArray().put(candidate));
                    JSONObject check=checks.length()==1?checks.optJSONObject(0):null;
                    if(check==null||!id.equals(check.optString("id"))||!check.optBoolean("valid",false)||check.optInt("answerIndex",-1)!=completed.optInt("answerIndex",-2)) {
                        String message=check==null?"":check.optString("reason","").trim();
                        throw new IllegalStateException(message.isEmpty()?"补全后仍有疑点，请手动修改":message);
                    }
                    resolve(new JSONObject().put("id",id).put("action","add").put("question",completed),true);
                } catch(Exception e) {
                    try { saveAiState(id,"failed",e.getMessage()==null?"AI补全失败，请重试":e.getMessage(),null); } catch(Exception ignored) { }
                } finally { aiProcessing.set(false); }
            });
        } catch(Exception e) {
            aiProcessing.set(false);saveAiState(id,"failed","AI补全未开始，请重试",null);throw e;
        }
        return new JSONObject().put("started",true);
    }

    private synchronized boolean saveAiState(String id,String state,String message,JSONObject draft) throws Exception {
        JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]"));
        for(int i=0;i<queue.length();i++) {
            JSONObject item=queue.getJSONObject(i);
            if(id.equals(item.optString("id"))&&"pending".equals(item.optString("state"))) {
                item.put("aiState",state).put("aiMessage",message);
                if(draft!=null)item.put("draft",draft);
                if(!operations.edit().putString("importReviewQueue",queue.toString()).commit()) throw new IllegalStateException("补全进度保存失败");
                return true;
            }
        }
        return false;
    }

    synchronized JSONObject resolve(JSONObject input) throws Exception { return resolve(input,false); }

    private synchronized JSONObject resolve(JSONObject input,boolean aiCompleted) throws Exception {
        migrateLegacy();
        String id=input.getString("id"),action=input.optString("action");
        if(!action.equals("add")&&!action.equals("skip")) throw new IllegalArgumentException("处理方式无效");
        JSONArray queue=new JSONArray(operations.getString("importReviewQueue","[]"));
        JSONObject issue=null;
        for(int i=0;i<queue.length();i++) if(id.equals(queue.getJSONObject(i).optString("id"))) { issue=queue.getJSONObject(i); break; }
        if(issue==null) throw new IllegalArgumentException("待确认题目已不存在，请刷新");
        if(!"pending".equals(issue.optString("state"))) { updateResult(issue,issue.optInt("addedCount",0)); return new JSONObject().put("added",0).put("alreadyHandled",true); }
        if("running".equals(issue.optString("aiState"))&&!aiCompleted) throw new IllegalArgumentException("该题正在AI补全，请等待完成");
        int added=0;
        if(action.equals("add")) {
            JSONObject edit=input.getJSONObject("question");
            String stem=edit.optString("stem").trim(),subject=edit.optString("subject").trim();
            int grade=edit.optInt("grade",0),answer=edit.optInt("answerIndex",-1);
            JSONObject chapter=TextbookKnowledge.chapter(context,edit.optString("chapterId"));
            if(chapter==null||chapter.optInt("grade")!=grade)throw new IllegalArgumentException("请选择所属教材章节");
            JSONArray choices=edit.optJSONArray("options");
            if(stem.isEmpty()||stem.length()>4000||subject.isEmpty()||subject.length()>30||grade<1||grade>12||choices==null||choices.length()!=4||answer<0||answer>3)
                throw new IllegalArgumentException("请补全学科、年级、题干、四个选项和正确答案");
            Set<String> unique=new HashSet<>();
            for(int i=0;i<4;i++) { String choice=choices.getString(i).trim(); if(choice.isEmpty()||choice.length()>1000||!unique.add(choice)) throw new IllegalArgumentException("选项不能为空、重复或超过 1000 字"); choices.put(i,choice); }
            JSONArray hints=new JSONArray();
            String explanation=edit.optString("explanation").trim();
            if(explanation.length()>2000) throw new IllegalArgumentException("解题提示最多 2000 字");
            hints.put(explanation.isEmpty()?"请仔细阅读题干，找出已知条件。":explanation).put("逐项比较四个选项，再选择正确答案。");
            String questionId="review_"+id;
            JSONObject raw=new JSONObject().put("id",questionId).put("rootId",questionId).put("grade",grade).put("subject",subject)
                .put("stem",stem).put("options",choices).put("answerIndex",answer).put("hints",hints).put("explanation",explanation).put("variants",new JSONArray()).put("aiGenerated",true);
            Question question=Question.fromStored(raw).inChapter(chapter.getString("id"),chapter.getString("title"),"上","base")
                .fromSource(issue.getString("sourceType"));
            added=QuestionBank.get(context).installRecognized(Collections.singletonList(question));
            issue.put("draft",question.toJson());
        }
        issue.put("state",action.equals("add")?"added":"skipped").put("addedCount",added);
        if(aiCompleted) issue.put("aiState","done").put("aiMessage","AI补全并核对完成");
        // 先保存已处理状态，随后更新展示结果；重复提交不会再次导入。
        if(!operations.edit().putString("importReviewQueue",queue.toString()).commit()) throw new IllegalStateException("处理结果保存失败，请重试");
        updateResult(issue,added);
        return new JSONObject().put("added",added).put("alreadyHandled",false);
    }

    private JSONArray without(JSONArray issues,String id) throws Exception {
        JSONArray kept=new JSONArray();
        if(issues!=null) for(int i=0;i<issues.length();i++) if(!id.equals(issues.getJSONObject(i).optString("id"))) kept.put(issues.getJSONObject(i));
        return kept;
    }

    private void updateResult(JSONObject issue,int added) throws Exception {
        String id=issue.getString("id");
        if("image".equals(issue.optString("sourceType"))) {
            JSONObject report=new JSONObject(exams.getString("report","{}"));
            JSONArray before=report.optJSONArray("importIssues"),after=without(before,id);
            if(before==null||after.length()==before.length()) return;
            report.put("importIssues",after).put("importCount",report.optInt("importCount",0)+added);
            if(!exams.edit().putString("report",report.toString()).commit()) throw new IllegalStateException("分析结果保存失败");
            operations.edit().putString("examStatus","图片处理完成：已导入 "+report.optInt("importCount")+" 道，需处理 "+after.length()+" 道").commit();
        } else {
            JSONArray before=new JSONArray(operations.getString("documentIssues","[]")),after=without(before,id);
            if(after.length()==before.length()) return;
            String status=operations.getString("documentStatus",""); int count=0;
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("已导入\\s*(\\d+)").matcher(status);
            if(match.find()) count=Integer.parseInt(match.group(1));
            if(!operations.edit().putString("documentIssues",after.toString()).putString("documentStatus","文档处理完成：已导入 "+(count+added)+" 道，需处理 "+after.length()+" 道").commit()) throw new IllegalStateException("文档结果保存失败");
        }
    }
}
