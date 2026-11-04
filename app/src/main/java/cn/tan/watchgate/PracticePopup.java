package cn.tan.watchgate;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;

/** 用悬浮答题窗显示家长发起的 50 题练习，避免依赖后台启动 Activity。 */
final class PracticePopup {
    private final GuardService service;
    private final PracticeBank bank;
    private final GateState state;
    private final WindowManager windows;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private View root;
    private String questionId="";
    private int selected=-1;
    private Runnable waitingRefresh;
    private boolean refreshingInPlace;
    private int parentTapCount;
    private long lastParentTap,twoFingerStart;
    private boolean twoFingerTap,consumeTouch;
    private float firstX,firstY,secondX,secondY;

    PracticePopup(GuardService service) {
        this.service=service; bank=PracticeBank.get(service); state=GateState.get(service);
        windows=(WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
    }

    boolean visible() { return root!=null; }

    void show() {
        if(!bank.enabled()) { hide(); return; }
        if(root!=null && !refreshingInPlace) return;
        if(!Settings.canDrawOverlays(service)) {
            state.error("悬浮窗权限已关闭，无法显示刷题窗。请由家长重新授权。"); return;
        }
        try { render(); }
        catch(Exception e) {
            String message=e.getMessage()==null?"无法读取刷题进度":e.getMessage();
            state.error("刷题弹窗失败："+message); showError(message);
        }
    }

    void hide() {
        if(waitingRefresh!=null) handler.removeCallbacks(waitingRefresh);
        waitingRefresh=null; removeWindow(); questionId=""; selected=-1;
    }

    private void removeWindow() {
        if(root!=null) {
            try { windows.removeView(root); } catch(RuntimeException ignored) { }
            root=null;
        }
    }

    private void render() throws Exception {
        JSONObject data=bank.view(); bank.ensureSupply(false);
        ScrollView scroll=new ScrollView(service) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                int action=event.getActionMasked();
                if(action==MotionEvent.ACTION_DOWN) { consumeTouch=false; twoFingerTap=false; }
                if(action==MotionEvent.ACTION_POINTER_DOWN) {
                    // 双指操作时取消选项按钮事件，避免误答。
                    if(!consumeTouch) {
                        MotionEvent cancel=MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL);
                        super.dispatchTouchEvent(cancel); cancel.recycle();
                    }
                    consumeTouch=true;
                    if(event.getPointerCount()==2) {
                        twoFingerTap=true; twoFingerStart=SystemClock.elapsedRealtime();
                        firstX=event.getX(0); firstY=event.getY(0); secondX=event.getX(1); secondY=event.getY(1);
                    } else twoFingerTap=false;
                }
                if(consumeTouch) {
                    if(action==MotionEvent.ACTION_MOVE&&event.getPointerCount()==2) {
                        float limit=Ui.dp(service,30);
                        if(Math.hypot(event.getX(0)-firstX,event.getY(0)-firstY)>limit||
                           Math.hypot(event.getX(1)-secondX,event.getY(1)-secondY)>limit) twoFingerTap=false;
                    }
                    if(action==MotionEvent.ACTION_CANCEL) { twoFingerTap=false; parentTapCount=0; consumeTouch=false; }
                    if(action==MotionEvent.ACTION_UP) {
                        long now=SystemClock.elapsedRealtime();
                        if(twoFingerTap&&now-twoFingerStart<=1000) {
                            parentTapCount=now-lastParentTap<=3000?parentTapCount+1:1; lastParentTap=now;
                            if(parentTapCount>=4) { parentTapCount=0; openParentExit(); }
                        } else parentTapCount=0;
                        twoFingerTap=false; consumeTouch=false;
                    }
                    return true;
                }
                return super.dispatchTouchEvent(event);
            }
            @Override public boolean dispatchKeyEvent(KeyEvent event) {
                if(event.getKeyCode()==KeyEvent.KEYCODE_BACK) {
                    if(event.getAction()==KeyEvent.ACTION_UP)
                        Toast.makeText(service,"请先完成刷题，或由家长关闭刷题模式",Toast.LENGTH_SHORT).show();
                    return true;
                }
                return super.dispatchKeyEvent(event);
            }
        };
        scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(service); outer.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        outer.setPadding(Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,24));
        LinearLayout card=Ui.column(service); card.setPadding(Ui.dp(service,12),Ui.dp(service,10),Ui.dp(service,12),Ui.dp(service,12));
        card.setBackground(Ui.shape(service,Ui.CARD,0));
        int available=service.getResources().getDisplayMetrics().widthPixels-Ui.dp(service,16);
        outer.addView(card,new LinearLayout.LayoutParams(Math.min(available,Ui.dp(service,680)),-2));
        LinearLayout heading=new LinearLayout(service); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(service,"刷题模式 · 50题",18,Ui.INK);
        title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        heading.addView(title,new LinearLayout.LayoutParams(0,-2,1)); card.addView(heading);

        int answered=data.optInt("answered",0);
        Ui.add(card,Ui.text(service,"已提交 "+answered+"/50 · 答对 "+data.optInt("correct",0)+" · 答错 "+data.optInt("wrong",0),14,Ui.MUTED),5);
        ProgressBar progress=new ProgressBar(service,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(PracticeBank.TARGET); progress.setProgress(Math.min(PracticeBank.TARGET,answered)); Ui.add(card,progress,6);

        if(data.optBoolean("complete")) {
            Ui.add(card,Ui.text(service,data.optInt("correct")+" 分 / 100",30,Ui.MINT),8);
            Ui.add(card,Ui.text(service,"本轮完成 · 正确率 "+data.optInt("accuracy")+"%",16,Ui.INK),4);
            Ui.add(card,Ui.text(service,"家长可从分享页停止刷题或开始下一轮。",14,Ui.MUTED),8);
        } else {
            JSONObject current=data.optJSONObject("current");
            if(current==null) {
                String status=data.optString("supplyStatus","");
                Ui.add(card,Ui.text(service,"正在准备下一道未做过的题…",17,Ui.INK),8);
                if(!status.isEmpty()) Ui.add(card,Ui.text(service,status,13,Ui.MUTED),5);
                Ui.add(card,Ui.button(service,data.optBoolean("generating")?"正在补题…":"重试补题",false,v->{
                    bank.ensureSupply(true); refreshWaiting();
                }),10);
            } else renderQuestion(card,current,answered);
        }
        scroll.addView(outer);
        FrameLayout frame=new FrameLayout(service); frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        frame.setFocusableInTouchMode(true); frame.requestFocus();
        mount(frame,"洋哥做题了 · 刷题");
        if(!data.optBoolean("complete")&&data.optJSONObject("current")==null && root!=null) scheduleWaitingRefresh(root);
    }

    private void renderQuestion(LinearLayout card,JSONObject current,int answered) throws Exception {
        JSONObject raw=current.getJSONObject("question"); Question question=Question.fromStored(raw);
        boolean submitted=current.optBoolean("submitted");
        if(!question.id.equals(questionId)) { questionId=question.id; selected=-1; }
        Ui.add(card,Ui.text(service,"第 "+(submitted?answered:answered+1)+" / 50 题 · "+question.subject+" · "+question.topic,13,Ui.MUTED),8);
        Ui.add(card,Ui.text(service,MathText.format(question.title),19,Ui.INK),7);
        android.util.DisplayMetrics metrics=service.getResources().getDisplayMetrics();
        android.graphics.Paint choicePaint=new android.graphics.Paint(); choicePaint.setTextSize(15f*metrics.scaledDensity);
        float maxChoiceWidth=0f;
        for(int i=0;i<question.choices.size();i++)
            maxChoiceWidth=Math.max(maxChoiceWidth,choicePaint.measureText((char)('A'+i)+". "+MathText.format(question.choices.get(i))));
        int screenWidthDp=Math.round(metrics.widthPixels/metrics.density);
        int cardWidthDp=Math.min(screenWidthDp-16,680),contentWidthDp=cardWidthDp-24;
        int contentWidthPx=Ui.dp(service,contentWidthDp),buttonChrome=Ui.dp(service,28);
        int columns=maxChoiceWidth+buttonChrome<=contentWidthPx/2f?Math.min(2,question.choices.size()):1;
        if(contentWidthDp>=540&&maxChoiceWidth+buttonChrome<=contentWidthPx/4f) columns=Math.min(4,question.choices.size());
        GridLayout options=new GridLayout(service); options.setColumnCount(columns); options.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        for(int i=0;i<question.choices.size();i++) {
            final int choice=i;
            Button option=Ui.button(service,(char)('A'+i)+". "+MathText.format(question.choices.get(i)),false,v->{
                if(!submitted) { selected=choice; redraw(); }
            });
            option.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); option.setMinHeight(Ui.dp(service,48));
            if(submitted) {
                option.setEnabled(false);
                if(i==question.answerIndex) paint(option,Color.rgb(224,244,230),Color.rgb(34,103,59));
                else if(i==current.optInt("choice",-1)) paint(option,Color.rgb(255,231,228),Color.rgb(160,43,39));
            } else if(i==selected) paint(option,Ui.MINT,Color.WHITE);
            GridLayout.LayoutParams optionLayout=new GridLayout.LayoutParams(GridLayout.spec(i/columns),GridLayout.spec(i%columns,1,1f));
            optionLayout.width=0; optionLayout.height=-2; int gap=Ui.dp(service,3); optionLayout.setMargins(gap,gap,gap,gap);
            options.addView(option,optionLayout);
        }
        Ui.add(card,options,4);
        if(!submitted) {
            Button submit=Ui.button(service,"提交答案",true,v->{
                try { bank.submit(question.id,selected); redraw(); }
                catch(Exception e) { Toast.makeText(service,e.getMessage()==null?"答案保存失败":e.getMessage(),Toast.LENGTH_LONG).show(); }
            });
            submit.setEnabled(selected>=0); Ui.add(card,submit,8);
            return;
        }
        boolean correct=current.optBoolean("correct");
        Ui.add(card,Ui.text(service,correct?"回答正确":"回答错误",17,correct?Color.rgb(34,103,59):Color.rgb(160,43,39)),7);
        if(!correct) {
            Ui.add(card,Ui.text(service,"基础知识",14,Ui.MINT),7);
            Ui.add(card,Ui.text(service,question.basicKnowledge.isEmpty()?"本题暂无整理好的基础知识。":MathText.format(question.basicKnowledge),14,Ui.INK),4);
        }
        Ui.add(card,Ui.text(service,"正确答案："+(char)('A'+question.answerIndex)+" · "+MathText.format(question.choices.get(question.answerIndex)),14,Ui.INK),4);
        Ui.add(card,Ui.text(service,explanation(raw),14,Ui.INK),7);
        Ui.add(card,Ui.button(service,answered==PracticeBank.TARGET?"查看本轮成绩":"下一题",true,v->{
            try {
                boolean restarted=bank.advance(); selected=-1; questionId=""; redraw();
                if(restarted) Toast.makeText(service,"本轮正确率低于90%，已重新开始50题",Toast.LENGTH_LONG).show();
            } catch(Exception e) { Toast.makeText(service,e.getMessage()==null?"无法进入下一题":e.getMessage(),Toast.LENGTH_LONG).show(); }
        }),10);
    }

    private void paint(Button button,int background,int foreground) {
        button.setBackground(Ui.shape(service,background,background==Ui.CARD?Color.rgb(222,225,231):0));
        button.setTextColor(foreground);
    }

    private String explanation(JSONObject raw) {
        String text=raw.optString("explanation",""); if(!text.isEmpty()) return MathText.format(text);
        JSONArray hints=raw.optJSONArray("hints"); StringBuilder result=new StringBuilder("参考思路：");
        if(hints!=null) for(int i=0;i<hints.length();i++) result.append("\n").append(MathText.format(hints.optString(i)));
        return result.toString();
    }

    private void redraw() {
        if(waitingRefresh!=null) handler.removeCallbacks(waitingRefresh);
        waitingRefresh=null; removeWindow(); show();
    }

    private void scheduleWaitingRefresh(View expected) {
        waitingRefresh=()->{
            waitingRefresh=null;
            if(root==expected&&bank.enabled()) refreshWaiting();
        };
        handler.postDelayed(waitingRefresh,3000);
    }
    private void refreshWaiting() {
        if(root==null) return;
        if(waitingRefresh!=null) handler.removeCallbacks(waitingRefresh);
        waitingRefresh=null; refreshingInPlace=true;
        try { show(); } finally { refreshingInPlace=false; }
    }

    private void showError(String message) {
        LinearLayout body=Ui.column(service); body.setPadding(Ui.dp(service,16),Ui.dp(service,16),Ui.dp(service,16),Ui.dp(service,16));
        body.setBackground(Ui.shape(service,Ui.CARD,0));
        Ui.add(body,Ui.text(service,"刷题窗暂时无法显示",18,Ui.INK),0);
        Ui.add(body,Ui.text(service,message,14,Ui.MUTED),8);
        Ui.add(body,Ui.button(service,"重新读取",true,v->redraw()),10);
        FrameLayout frame=new FrameLayout(service); frame.setBackgroundColor(Ui.BG);
        FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP|Gravity.CENTER_HORIZONTAL);
        box.setMargins(Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,8),0); frame.addView(body,box);
        mount(frame,"洋哥做题了 · 刷题");
    }

    private void mount(View frame,String title) {
        if(refreshingInPlace && root instanceof FrameLayout) {
            // 等待补题时保留外层窗口，原位替换内部内容。
            FrameLayout container=(FrameLayout)root;
            container.removeAllViews(); container.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            frame.requestFocus(); return;
        }
        root=frame;
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
        params.gravity=Gravity.TOP|Gravity.START; params.setTitle(title);
        try { windows.addView(root,params); }
        catch(RuntimeException e) { root=null; state.error("无法显示刷题窗，请检查悬浮窗权限："+e.getClass().getSimpleName()); }
    }

    private void openParentExit() {
        try {
            service.startActivity(new Intent(service,MainActivity.class).putExtra("gesture_exit",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP));
            hide();
        } catch(RuntimeException e) {
            Toast.makeText(service,"无法打开家长验证，请由家长网页放行",Toast.LENGTH_LONG).show();
        }
    }
}
