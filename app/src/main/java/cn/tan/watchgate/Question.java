package cn.tan.watchgate;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 一道题的题干、选项、正确项、分步提示及同考点变体。 */
public final class Question {
    public final String id, rootId, subject, topic, title;
    /** 题目所属年级；旧题库记录缺少该字段时按六年级兼容。 */
    public final int grade;
    /** AI 估算的作答时长（秒）；旧题包默认 60 秒，不设固定上限。 */
    public final long timeLimitSeconds;
    public final String modelType;
    public final boolean aiGenerated;
    public String chapterId="", chapterTitle="", term="", questionType="base", sourceType="", explanation="", basicKnowledge="";
    public final List<String> knowledgePoints=new ArrayList<>();
    public final List<String> choices, hints;
    public final List<Question> variants;
    public final int answerIndex;

    /** 本地备用题，AI 题包不可用时仍能正常答题。 */
    public Question(int level) {
        this(level, 6);
    }

    public Question(int level, int grade) {
        SecureRandom random = new SecureRandom();
        int a, b, answer;
        String operator;
        if (level == 3) {
            a = 2 + random.nextInt(8); b = 2 + random.nextInt(8);
            if (random.nextBoolean()) { operator = "×"; answer = a * b; }
            else { operator = "÷"; answer = b; int product = a * b; a = product; }
        } else {
            int max = level == 2 ? 99 : 20;
            a = random.nextInt(max + 1); b = random.nextInt(max + 1);
            if (random.nextBoolean()) {
                if (a < b) { int temp = a; a = b; b = temp; }
                operator = "−"; answer = a - b;
            } else {
                b = random.nextInt(max - a + 1); operator = "+"; answer = a + b;
            }
        }
        id = UUID.randomUUID().toString(); rootId = id; subject = "数学"; topic = "基础运算"; this.grade = grade; timeLimitSeconds = 60;
        modelType="number_line"; aiGenerated=false;
        title = a + " " + operator + " " + b + " = ?";
        List<String> values = new ArrayList<>(); values.add(String.valueOf(answer));
        while (values.size() < 4) {
            String choice = String.valueOf(Math.max(0, answer + random.nextInt(19) - 9));
            if (!values.contains(choice)) values.add(choice);
        }
        Collections.shuffle(values, random); choices = values; answerIndex = choices.indexOf(String.valueOf(answer));
        hints = new ArrayList<>();
        hints.add("先判断运算符，再把题目拆成一步一步的小计算。");
        hints.add("按运算规则重新计算一次，注意数位和运算顺序。");
        hints.add("用相反运算检查你的结果是否合理。");
        variants = new ArrayList<>();
    }

    private Question(String id, String rootId, String subject, String topic, String title, int grade, long timeLimitSeconds, String modelType, boolean aiGenerated,
                     List<String> choices, int answerIndex, List<String> hints, List<Question> variants) {
        this.id = id; this.rootId = rootId; this.subject = subject; this.topic = topic; this.title = title;
        this.grade = grade; this.timeLimitSeconds = timeLimitSeconds; this.modelType=modelType; this.aiGenerated=aiGenerated;
        this.choices = choices; this.answerIndex = answerIndex; this.hints = hints; this.variants = variants;
    }

    public static Question fromAi(JSONObject source, String subject, int grade) throws JSONException {
        String id = UUID.randomUUID().toString();
        // 即使模型附带变体，初次生成也只保存题目本身。
        JSONObject original = new JSONObject(source.toString()); original.remove("variants");
        Question question = read(original, subject, id, id, true, grade, true);
        return question.shuffled();
    }

    private static Question read(JSONObject source, String subject, String id, String rootId, boolean root, int grade, boolean aiGenerated) throws JSONException {
        String title = source.getString("stem").trim();
        String topic = source.optString("topic", "综合知识").trim();
        long timeLimitSeconds = source.optLong("timeLimitSeconds", 60L);
        if (timeLimitSeconds <= 0) timeLimitSeconds = 60L;
        String modelType=source.optString("modelType","none");
        if(!subject.equals("数学")) modelType="none";
        else if(!java.util.Arrays.asList("fraction","ratio","circle","area","number_line","balance","bars","grouping").contains(modelType)) modelType=modelForTopic(topic);
        JSONArray optionArray = source.getJSONArray("options");
        if (title.isEmpty() || optionArray.length() != 4) throw new JSONException("题干或选项数量无效");
        List<String> choices = new ArrayList<>();
        for (int i = 0; i < optionArray.length(); i++) {
            String choice = optionArray.getString(i).trim();
            if (choice.isEmpty() || choices.contains(choice)) throw new JSONException("选项为空或重复");
            choices.add(choice);
        }
        int answer = source.getInt("answerIndex");
        if (answer < 0 || answer >= choices.size()) throw new JSONException("正确选项编号无效");
        JSONArray hintArray = source.getJSONArray("hints");
        if (hintArray.length() < 2 || hintArray.length() > 5) throw new JSONException("提示数量无效");
        List<String> hints = new ArrayList<>();
        for (int i = 0; i < hintArray.length(); i++) {
            String hint = hintArray.getString(i).trim();
            if (hint.isEmpty()) throw new JSONException("提示内容为空");
            hints.add(hint);
        }
        List<Question> variants = new ArrayList<>();
        JSONArray variantArray = root ? source.optJSONArray("variants") : null;
        // 已保存的题目可能多次补充错题变体，读取时保留全部变体。
        // 新题和旧题都不限制变体数量，每道变体仍单独校验内容。
        if (variantArray != null) for (int i = 0; i < variantArray.length(); i++) {
            JSONObject variant=variantArray.getJSONObject(i);
            String variantId=variant.optString("id",""); if(variantId.isEmpty()) variantId=UUID.randomUUID().toString();
            variants.add(read(variant, subject, variantId, rootId, false, grade, aiGenerated));
        }
        Question result=new Question(id, rootId, subject, topic, title, grade, timeLimitSeconds, modelType, aiGenerated, choices, answer, hints, variants);
        result.chapterId=source.optString("chapterId",""); result.chapterTitle=source.optString("chapterTitle","");
        result.term=source.optString("term",""); result.questionType=source.optString("questionType","base"); result.sourceType=source.optString("sourceType",""); result.explanation=source.optString("explanation","");
        result.basicKnowledge=source.optString("basicKnowledge","").trim();
        JSONArray points=source.optJSONArray("knowledgePoints");
        if(points!=null) for(int i=0;i<points.length();i++) { String point=points.optString(i,"").trim(); if(!point.isEmpty()&&!result.knowledgePoints.contains(point)) result.knowledgePoints.add(point); }
        if(result.knowledgePoints.isEmpty()&&!topic.isEmpty()) result.knowledgePoints.add(topic);
        return result;
    }

    private static String modelForTopic(String topic) {
        if(topic.contains("分数") || topic.contains("百分")) return "fraction";
        if(topic.contains("比例") || topic.contains("比值")) return "ratio";
        if(topic.contains("圆") || topic.contains("扇形")) return "circle";
        if(topic.contains("面积") || topic.contains("长方形")) return "area";
        if(topic.contains("统计") || topic.contains("图表")) return "bars";
        if(topic.contains("方程") || topic.contains("等式")) return "balance";
        if(topic.contains("数轴") || topic.contains("负数")) return "number_line";
        return "none";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject value = new JSONObject(); value.put("id", id); value.put("rootId", rootId); value.put("grade", grade);
        value.put("timeLimitSeconds", timeLimitSeconds);
        value.put("modelType",modelType); value.put("aiGenerated",aiGenerated);
        value.put("chapterId",chapterId); value.put("chapterTitle",chapterTitle); value.put("term",term); value.put("questionType",questionType); value.put("sourceType",sourceType); value.put("explanation",explanation);
        value.put("basicKnowledge",basicKnowledge);
        JSONArray points=new JSONArray(); for(String point:knowledgePoints) points.put(point); value.put("knowledgePoints",points);
        value.put("subject", subject); value.put("topic", topic); value.put("stem", title);
        JSONArray options = new JSONArray(); for (String choice : choices) options.put(choice); value.put("options", options);
        value.put("answerIndex", answerIndex);
        JSONArray hintArray = new JSONArray(); for (String hint : hints) hintArray.put(hint); value.put("hints", hintArray);
        JSONArray variantArray = new JSONArray(); for (Question variant : variants) variantArray.put(variant.toJson());
        value.put("variants", variantArray); return value;
    }

    public static Question fromStored(JSONObject value) throws JSONException {
        String id = value.getString("id"), rootId = value.optString("rootId", id);
        return read(value, value.optString("subject", "数学"), id, rootId, true, value.optInt("grade", 6), value.optBoolean("aiGenerated",false));
    }

    Question withRootId(String value) {
        return copyContext(new Question(UUID.randomUUID().toString(), value, subject, topic, title, grade, timeLimitSeconds, modelType, aiGenerated,
            new ArrayList<>(choices), answerIndex, new ArrayList<>(hints), new ArrayList<>()));
    }

    /** 原题和变体共用章节归属，历史题没有章节时保留为空。 */
    Question inChapter(String chapterId,String chapterTitle,String term,String questionType) {
        this.chapterId=chapterId; this.chapterTitle=chapterTitle; this.term=term; this.questionType=questionType;
        for(Question variant:variants) variant.inChapter(chapterId,chapterTitle,term,questionType);
        return this;
    }

    /** 原题与后续错题变体继承相同来源。 */
    Question fromSource(String source) {
        sourceType=source;
        for(Question variant:variants) variant.fromSource(source);
        return this;
    }

    private Question copyContext(Question target) {
        target.chapterId=chapterId; target.chapterTitle=chapterTitle; target.term=term; target.questionType=questionType;
        target.sourceType=sourceType; target.explanation=explanation; target.basicKnowledge=basicKnowledge; target.knowledgePoints.addAll(knowledgePoints); return target;
    }

    private Question shuffled() {
        List<Integer> order = new ArrayList<>(); for (int i = 0; i < choices.size(); i++) order.add(i);
        Collections.shuffle(order, new SecureRandom());
        List<String> shuffledChoices = new ArrayList<>(); int shuffledAnswer = 0;
        for (int i = 0; i < order.size(); i++) {
            int oldIndex = order.get(i); shuffledChoices.add(choices.get(oldIndex));
            if (oldIndex == answerIndex) shuffledAnswer = i;
        }
        List<Question> shuffledVariants = new ArrayList<>();
        for (Question variant : variants) shuffledVariants.add(variant.shuffled());
        return copyContext(new Question(id, rootId, subject, topic, title, grade, timeLimitSeconds, modelType, aiGenerated, shuffledChoices, shuffledAnswer,
            new ArrayList<>(hints), shuffledVariants));
    }
}
