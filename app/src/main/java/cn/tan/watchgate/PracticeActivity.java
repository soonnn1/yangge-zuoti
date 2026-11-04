package cn.tan.watchgate;

import android.app.*;
import android.os.*;
import android.graphics.Color;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** 驾考式顺序刷题：每题仅提交一次，查看解析后进入下一题。 */
public final class PracticeActivity extends Activity {
    public static volatile boolean visible;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PracticeBank bank;
    private String fingerprint="",questionId="";
    private int selected=-1;
    private final Runnable tick=new Runnable(){public void run(){update();if(visible)handler.postDelayed(this,1000);}};

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);bank=PracticeBank.get(this);
        if(saved!=null){questionId=saved.getString("questionId","");selected=saved.getInt("selected",-1);}
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    @Override protected void onResume(){super.onResume();if(!bank.enabled()){finish();return;}visible=true;GuardService.hidePracticePopup();GuardService.refresh();handler.removeCallbacks(tick);handler.post(tick);}
    @Override protected void onPause(){visible=false;handler.removeCallbacks(tick);GuardService.refresh();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("questionId",questionId);out.putInt("selected",selected);super.onSaveInstanceState(out);}
    @Override public void onBackPressed(){requestExit();}

    private void update() {
        if(!bank.enabled()){finish();return;}
        try {
            JSONObject view=bank.view();bank.ensureSupply(false);
            String next=view.toString();if(next.equals(fingerprint))return;fingerprint=next;render(view);
        } catch(Exception e){String message=e.getMessage()==null?"无法读取刷题进度":e.getMessage();if(message.equals(fingerprint))return;fingerprint=message;showError(message);}
    }
    private LinearLayout page(String title) {
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(this);outer.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);outer.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,12));
        LinearLayout body=Ui.column(this);int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,20),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2));scroll.addView(outer);setContentView(scroll);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=Ui.text(this,title,20,Ui.INK);label.setTypeface(null,android.graphics.Typeface.BOLD);heading.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button exit=Ui.button(this,"退出",false,v->requestExit());heading.addView(exit,new LinearLayout.LayoutParams(-2,Ui.dp(this,48)));Ui.add(body,heading,0);return body;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=Ui.column(this);box.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));box.setBackground(Ui.shape(this,Ui.CARD,0));Ui.add(parent,box,10);return box;
    }
    private String explanation(JSONObject raw) {
        String text=raw.optString("explanation","");if(!text.isEmpty())return MathText.format(text);
        JSONArray hints=raw.optJSONArray("hints");StringBuilder result=new StringBuilder("参考思路：");
        if(hints!=null)for(int i=0;i<hints.length();i++)result.append("\n").append(MathText.format(hints.optString(i)));
        return result.toString();
    }
    private void render(JSONObject view) throws Exception {
        LinearLayout body=page(view.optBoolean("complete")?"本轮成绩":"刷题模式 · 顺序练习");
        if(view.optBoolean("complete")){renderScore(body,view);return;}
        int answered=view.getInt("answered");JSONObject current=view.optJSONObject("current");
        Ui.add(body,Ui.text(this,"每轮 50 题 · 已提交 "+answered+"/50 · 答对 "+view.getInt("correct")+" · 答错 "+view.getInt("wrong"),14,Ui.MUTED),6);
        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(50);progress.setProgress(answered);Ui.add(body,progress,6);
        if(view.optBoolean("builtinFallback"))Ui.add(body,Ui.text(this,"系统内置备用题 · 小学1—6年级",13,Ui.MUTED),6);
        String supply=view.optString("supplyStatus","");if(!supply.isEmpty())Ui.add(body,Ui.text(this,supply,13,Ui.MUTED),6);
        if(current==null){
            LinearLayout waiting=card(body);Ui.add(waiting,Ui.text(this,"正在准备下一道未做过的题…",18,Ui.INK),0);
            Button retry=Ui.button(this,view.optBoolean("generating")?"正在补题…":"重试补题",true,v->{bank.ensureSupply(true);fingerprint="";update();});retry.setEnabled(!view.optBoolean("generating"));Ui.add(waiting,retry,12);return;
        }
        JSONObject raw=current.getJSONObject("question");Question question=Question.fromStored(raw);boolean submitted=current.optBoolean("submitted");
        if(!question.id.equals(questionId)){questionId=question.id;selected=-1;}
        LinearLayout questionBox=card(body);Ui.add(questionBox,Ui.text(this,"第 "+(submitted?answered:answered+1)+" / 50 题 · "+question.subject+" · "+question.topic,13,Ui.MUTED),0);
        Ui.add(questionBox,Ui.text(this,MathText.format(question.title),19,Ui.INK),8);
        List<Button> options=new ArrayList<>();Button submit=Ui.button(this,"提交答案",true,v->{
            try{bank.submit(question.id,selected);fingerprint="";update();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
        });
        submit.setEnabled(selected>=0);
        // 按实际选项文字和可用宽度选择列数，长选项仍独占一行。
        android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
        android.graphics.Paint choicePaint=new android.graphics.Paint();choicePaint.setTextSize(16f*metrics.scaledDensity);
        float maxChoiceWidth=0;
        for(int i=0;i<question.choices.size();i++)maxChoiceWidth=Math.max(maxChoiceWidth,choicePaint.measureText((char)('A'+i)+". "+MathText.format(question.choices.get(i))));
        int screenWidthDp=Math.round(metrics.widthPixels/metrics.density);
        int contentWidthDp=Math.min(screenWidthDp-20,720)-24;
        int contentWidthPx=Ui.dp(this,contentWidthDp),buttonChrome=Ui.dp(this,28);
        int columns=maxChoiceWidth+buttonChrome<=contentWidthPx/2f?Math.min(2,question.choices.size()):1;
        if(contentWidthDp>=540&&maxChoiceWidth+buttonChrome<=contentWidthPx/4f)columns=Math.min(4,question.choices.size());
        GridLayout grid=new GridLayout(this);grid.setColumnCount(columns);grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        for(int i=0;i<question.choices.size();i++) {
            final int choice=i;
            Button option=Ui.button(this,String.valueOf((char)('A'+i))+". "+MathText.format(question.choices.get(i)),false,v->{
                selected=choice;for(int j=0;j<options.size();j++)paintOption(options.get(j),j==selected?Ui.MINT:Ui.CARD,j==selected?Color.WHITE:Ui.INK);submit.setEnabled(true);
            });
            option.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);option.setMinHeight(Ui.dp(this,48));option.setEnabled(!submitted);
            if(submitted){
                if(i==question.answerIndex)paintOption(option,Color.rgb(224,244,230),Color.rgb(34,103,59));
                else if(i==current.optInt("choice",-1))paintOption(option,Color.rgb(255,231,228),Color.rgb(160,43,39));
            } else if(i==selected)paintOption(option,Ui.MINT,Color.WHITE);
            GridLayout.LayoutParams layout=new GridLayout.LayoutParams(GridLayout.spec(i/columns),GridLayout.spec(i%columns,1,1f));
            layout.width=0;layout.height=-2;int gap=Ui.dp(this,3);layout.setMargins(gap,gap,gap,gap);
            options.add(option);grid.addView(option,layout);
        }
        Ui.add(questionBox,grid,8);
        if(!submitted){Ui.add(questionBox,submit,12);return;}
        LinearLayout feedback=card(body);boolean correct=current.optBoolean("correct");
        Ui.add(feedback,Ui.text(this,correct?"回答正确":"回答错误",18,correct?Color.rgb(34,103,59):Color.rgb(160,43,39)),0);
        if(!correct) {
            Ui.add(feedback,Ui.text(this,"基础知识",14,Ui.MINT),7);
            Ui.add(feedback,Ui.text(this,question.basicKnowledge.isEmpty()?"本题暂无整理好的基础知识。":MathText.format(question.basicKnowledge),14,Ui.INK),4);
        }
        Ui.add(feedback,Ui.text(this,"正确答案："+(char)('A'+question.answerIndex)+" · "+MathText.format(question.choices.get(question.answerIndex)),16,Ui.INK),6);
        Ui.add(feedback,Ui.text(this,explanation(raw),15,Ui.INK),8);
        if("running".equals(current.optString("explainState")))Ui.add(feedback,Ui.text(this,"正在生成详细解析，可先查看参考思路。",13,Ui.MUTED),4);
        else if("failed".equals(current.optString("explainState")))Ui.add(feedback,Ui.text(this,"详细解析暂不可用，已保留参考思路。",13,Ui.MUTED),4);
        Ui.add(body,Ui.button(this,answered==50?"查看本轮成绩":"下一题",true,v->{try{boolean restarted=bank.advance();selected=-1;fingerprint="";update();if(restarted)Toast.makeText(this,"本轮正确率低于90%，已重新开始50题",Toast.LENGTH_LONG).show();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}),12);
    }
    private void paintOption(Button option,int background,int color){option.setBackground(Ui.shape(this,background,background==Ui.CARD?Color.rgb(222,225,231):0));option.setTextColor(color);}
    private void renderScore(LinearLayout body,JSONObject view) throws Exception {
        LinearLayout result=card(body);Ui.add(result,Ui.text(this,view.getInt("score")+" 分 / 100",36,Ui.MINT),0);
        Ui.add(result,Ui.text(this,"已完成 50 题 · 答对 "+view.getInt("correct")+" · 答错 "+view.getInt("wrong")+" · 正确率 "+view.getInt("accuracy")+"%",15,Ui.INK),6);
        Ui.add(result,Ui.text(this,"每题 2 分，以第一次提交的答案计分。",13,Ui.MUTED),4);
        JSONObject weaknesses=view.getJSONObject("weaknesses");
        if(weaknesses.length()==0)Ui.add(result,Ui.text(this,"本轮全部答对，未发现错题。",16,Ui.INK),10);
        else {
            Ui.add(result,Ui.text(this,"薄弱点总结",17,Ui.INK),12);List<String> topics=new ArrayList<>();Iterator<String> keys=weaknesses.keys();while(keys.hasNext())topics.add(keys.next());
            Collections.sort(topics,(a,b)->Integer.compare(weaknesses.optInt(b),weaknesses.optInt(a)));
            for(String topic:topics)Ui.add(result,Ui.text(this,topic+"：答错 "+weaknesses.optInt(topic)+" 道",14,Ui.MUTED),4);
        }
        JSONArray wrongs=view.getJSONArray("wrongs");
        if(wrongs.length()>0){
            LinearLayout list=Ui.column(this);list.setVisibility(View.GONE);
            Button toggle=Ui.button(this,"查看错题与解析（"+wrongs.length()+"）",false,v->list.setVisibility(list.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));Ui.add(body,toggle,10);Ui.add(body,list,6);
            for(int i=0;i<wrongs.length();i++) {
                JSONObject answer=wrongs.getJSONObject(i),raw=answer.getJSONObject("question");Question q=Question.fromStored(raw);LinearLayout item=card(list);
                Ui.add(item,Ui.text(this,(i+1)+". "+MathText.format(q.title),16,Ui.INK),0);
                Ui.add(item,Ui.text(this,"你的答案："+(char)('A'+answer.getInt("choice"))+" · "+MathText.format(q.choices.get(answer.getInt("choice")))+"\n正确答案："+(char)('A'+q.answerIndex)+" · "+MathText.format(q.choices.get(q.answerIndex)),14,Ui.MUTED),6);
                Ui.add(item,Ui.text(this,explanation(raw),14,Ui.INK),6);
            }
        }
        Ui.add(body,Ui.text(this,"本轮已完成，下一轮由家长端开启。",15,Ui.MUTED),12);
    }
    private void showError(String message){LinearLayout body=page("刷题模式");Ui.add(body,Ui.text(this,message,17,Ui.INK),12);Ui.add(body,Ui.button(this,"重新读取",true,v->{fingerprint="";update();}),12);}

    private void requestExit() {
        GateState state=GateState.get(this);
        if(!state.hasPin()){stopAndExit();return;}
        if(state.pinWaitMs()>0){Toast.makeText(this,"密码尝试过多，请稍后再试",Toast.LENGTH_LONG).show();return;}
        EditText input=new EditText(this);input.setSingleLine(true);input.setHint("家长密码");input.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout fields=Ui.column(this);fields.setPadding(Ui.dp(this,16),Ui.dp(this,8),Ui.dp(this,16),0);fields.addView(input);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("退出刷题 · 保留进度").setView(fields).setPositiveButton("确认退出",null).setNegativeButton("继续刷题",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(state.checkPin(input.getText().toString())){dialog.dismiss();stopAndExit();}
            else {input.setText("");input.setError(state.pinWaitMs()>0?"尝试过多，请等30秒":"密码不正确");}
        }));dialog.show();
    }
    private void stopAndExit(){try{bank.stopByParent();GuardService.refresh();finish();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}
}
