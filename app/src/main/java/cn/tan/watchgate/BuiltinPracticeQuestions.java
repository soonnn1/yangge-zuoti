package cn.tan.watchgate;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 题库为空时供儿童端刷题使用的固定小学数学题，不调用 AI。 */
final class BuiltinPracticeQuestions {
    private static JSONArray cached;

    static synchronized JSONArray load(Context context) throws Exception {
        if(cached==null) {
            try(InputStream input=context.getAssets().open("builtin_primary_questions.json");ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[4096];int count;while((count=input.read(buffer))!=-1)output.write(buffer,0,count);
                JSONArray source=new JSONObject(new String(output.toByteArray(),StandardCharsets.UTF_8)).getJSONArray("questions");
                if(source.length()!=50)throw new IllegalStateException("系统内置题库数量不正确");
                JSONArray result=new JSONArray();
                for(int i=0;i<source.length();i++) {
                    JSONObject question=new JSONObject(source.getJSONObject(i).toString());
                    int grade=question.getInt("grade");String id=question.getString("id"),topic=question.getString("topic");
                    question.put("rootId",id).put("subject","数学")
                        .put("chapterId","builtin-primary-g"+grade).put("chapterTitle","小学"+grade+"年级")
                        .put("term","").put("questionType","practice").put("sourceType","system")
                        .put("aiGenerated",false)
                        .put("hints",new JSONArray()
                            .put("先找出题目中已知的数量、单位和所求问题。")
                            .put("围绕“"+topic+"”列出算式，按步骤计算。")
                            .put("把结果代回题目条件，检查答案是否合理。"));
                    result.put(question);
                }
                cached=result;
            }
        }
        return new JSONArray(cached.toString());
    }
}
