package cn.tan.watchgate;

import android.content.*;
import android.graphics.PixelFormat;
import android.media.*;
import android.media.session.*;
import android.media.session.MediaController;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** 不拦截系统电源操作；只在受管控应用上覆盖答题页，家长入口始终可见。 */
public final class QuizOverlay {
    private final GuardService service;
    private final GateState state;
    private final WindowManager windows;
    private final Handler timerHandler = new Handler(Looper.getMainLooper());
    private View root;
    private TextView timerLabel, remainingLabel;
    private Runnable timerTick;
    private Runnable waitingRefresh;
    private boolean refreshingInPlace;
    private long lastPause;
    private AudioFocusRequest focus;
    private String modelQuestionId="", modelError="";
    private JSONObject modelData;
    private boolean modelOpen, modelLoading;
    private int modelRequestSerial;
    private int parentTapCount;
    private long lastParentTap, twoFingerStart;
    private boolean twoFingerTap, consumeTouch;
    private float firstX, firstY, secondX, secondY;
    QuizOverlay(GuardService service) {
        this.service = service; state = GateState.get(service); windows = (WindowManager)service.getSystemService(Context.WINDOW_SERVICE);
    }
    public void show() {
        if (MainActivity.parentBypass() || !state.controlActive() || !service.isBlocking()) return;
        if (root != null && !refreshingInPlace) { if (SystemClock.elapsedRealtime() - lastPause > 2000) pauseMedia(); return; }
        if (!Settings.canDrawOverlays(service)) { state.error("悬浮窗权限已关闭，无法拦截。请由家长重新授权。"); return; }
        try {
            ScrollView scroll = new ScrollView(service) {
                @Override public boolean dispatchTouchEvent(MotionEvent event) {
                    int action=event.getActionMasked();
                    if(action==MotionEvent.ACTION_DOWN) { consumeTouch=false; twoFingerTap=false; }
                    if(action==MotionEvent.ACTION_POINTER_DOWN) {
                        // 双指触碰时取消子控件的点击，避免误选答案。
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
                        if(action==MotionEvent.ACTION_MOVE && event.getPointerCount()==2) {
                            float limit=Ui.dp(service,30);
                            if(Math.hypot(event.getX(0)-firstX,event.getY(0)-firstY)>limit ||
                               Math.hypot(event.getX(1)-secondX,event.getY(1)-secondY)>limit) twoFingerTap=false;
                        }
                        if(action==MotionEvent.ACTION_CANCEL) { twoFingerTap=false; parentTapCount=0; consumeTouch=false; }
                        if(action==MotionEvent.ACTION_UP) {
                            long now=SystemClock.elapsedRealtime();
                            if(twoFingerTap && now-twoFingerStart<=1000) {
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
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                        if (event.getAction() == KeyEvent.ACTION_UP)
                            Toast.makeText(service,"请先答题，或由家长放行",Toast.LENGTH_SHORT).show();
                        return true;
                    }
                    return super.dispatchKeyEvent(event);
                }
            };
            scroll.setFillViewport(true); scroll.setBackgroundColor(Ui.BG);
            FrameLayout frame=new FrameLayout(service); frame.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
            LinearLayout outer = Ui.column(service); outer.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            outer.setPadding(Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,90));
            LinearLayout card = Ui.column(service); card.setPadding(Ui.dp(service,12),Ui.dp(service,10),Ui.dp(service,12),Ui.dp(service,10));
            card.setBackground(Ui.shape(service,Ui.CARD,0));
            int available = service.getResources().getDisplayMetrics().widthPixels - Ui.dp(service,16);
            outer.addView(card,new LinearLayout.LayoutParams(Math.min(available,Ui.dp(service,680)),-2));
            QuestionBank bank = QuestionBank.get(service);
            boolean blacklistSession=bank.isBlacklistSession();
            LinearLayout headingRow=new LinearLayout(service); headingRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView heading=Ui.text(service,"洋哥做题了",18,Ui.INK);
            heading.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            headingRow.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
            if(blacklistSession) {
                TextView exit=Ui.text(service,"×",30,Ui.MUTED); exit.setGravity(Gravity.CENTER); exit.setContentDescription("输入家长密码退出");
                exit.setOnClickListener(v -> openBlacklistExit(bank.blacklistPackage()));
                headingRow.addView(exit,new LinearLayout.LayoutParams(Ui.dp(service,40),Ui.dp(service,40)));
            }
            card.addView(headingRow);
            Ui.add(card,Ui.text(service,blacklistSession?"黑名单挑战 · 答完 50 道题后放行":"答对后继续使用",13,Ui.MUTED),0);
            int loanIndex=bank.loanCount();
            if(loanIndex<4) {
                int[] minutes={2,3,5,10}, added={1,3,10,0};
                LinearLayout loanRow=new LinearLayout(service); loanRow.setGravity(Gravity.CENTER_VERTICAL);
                TextView info=Ui.text(service,"借 "+minutes[loanIndex]+" 分钟 · 加 "+added[loanIndex]+" 题",13,Ui.MUTED);
                loanRow.addView(info);
                Button loan=Ui.button(service,"贷款",false,v -> {
                    try {
                        int borrowed=bank.borrowTime();
                        if(borrowed<=0) { Toast.makeText(service,"本轮暂时不能借用",Toast.LENGTH_SHORT).show(); return; }
                        service.released(); hide(); GuardService.refresh();
                        Toast.makeText(service,"已借 "+borrowed+" 分钟，到期继续答题",Toast.LENGTH_LONG).show();
                    } catch(Exception e) { Toast.makeText(service,"贷款保存失败，请重试",Toast.LENGTH_LONG).show(); }
                });
                loan.setTextSize(13); loan.setMinWidth(0); loan.setMinimumWidth(0); loan.setMinHeight(Ui.dp(service,40));
                LinearLayout.LayoutParams buttonLayout=new LinearLayout.LayoutParams(-2,Ui.dp(service,40)); buttonLayout.leftMargin=Ui.dp(service,8);
                loanRow.addView(loan,buttonLayout);
                FrameLayout.LayoutParams loanLayout=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
                loanLayout.setMargins(0,0,Ui.dp(service,14),Ui.dp(service,16)); frame.addView(loanRow,loanLayout);
            }
            QuestionBank.View challenge;
            try { challenge = bank.current(state.level()); }
            catch (QuestionBank.NoFreshQuestion waiting) {
                Ui.add(card,Ui.text(service,bank.preparingQuestions()?"正在生成新题":"暂时没有新题",20,Ui.INK),14);
                String status=bank.waitingStatus();
                Ui.add(card,Ui.text(service,status,14,Ui.MUTED),8);
                Ui.add(card,Ui.button(service,"重新加载",false,v -> refreshWaiting()),12);
                scroll.addView(outer); mountFrame(frame,"洋哥做题了 · 等待新题");
                scheduleWaitingRefresh(); pauseMedia(); return;
            }
            catch (Exception e) {
                // 保留异常类型和项目内出错位置，方便定位旧题目或进度兼容问题。
                Throwable cause=e;
                while(cause.getCause()!=null && cause.getCause()!=cause) cause=cause.getCause();
                String location="";
                for(StackTraceElement entry:cause.getStackTrace()) {
                    if(entry.getClassName().startsWith("cn.tan.watchgate.")) {
                        location=entry.getClassName().substring("cn.tan.watchgate.".length())+":"+entry.getLineNumber(); break;
                    }
                }
                android.util.Log.e("WatchGateQuiz","读取题目状态失败",e);
                state.error("题目状态读取失败："+cause.getClass().getSimpleName()
                    +(cause.getMessage()==null?"":" · "+cause.getMessage())+(location.isEmpty()?"":" · "+location));
                if(refreshingInPlace && root!=null) scheduleWaitingRefresh();
                return;
            }
            Question question = challenge.question;
            if(!question.id.equals(modelQuestionId)) {
                modelQuestionId=question.id; modelData=null; modelOpen=false; modelLoading=false; modelError=""; modelRequestSerial++;
            }
            TextView modelIcon=Ui.text(service,"◈ 演示",13,Ui.MINT); modelIcon.setGravity(Gravity.CENTER);
            modelIcon.setContentDescription("模型演示"); modelIcon.setFocusable(true);
            modelIcon.setOnClickListener(v -> {
                if(modelLoading) return;
                if(modelData!=null) { modelOpen=!modelOpen; hide(); show(); }
                else requestModel(question);
            });
            headingRow.addView(modelIcon,1,new LinearLayout.LayoutParams(Ui.dp(service,64),Ui.dp(service,40)));
            LinearLayout metaRow=new LinearLayout(service); metaRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView subject=Ui.text(service,question.subject + (question.topic.isEmpty()?"":" · "+question.topic),13,Ui.MUTED);
            subject.setSingleLine(true); subject.setEllipsize(android.text.TextUtils.TruncateAt.END);
            metaRow.addView(subject,new LinearLayout.LayoutParams(0,-2,1));
            if(question.aiGenerated) {
                TextView ai=Ui.text(service,"AI 生成",11,Ui.MUTED);
                LinearLayout.LayoutParams aiParams=new LinearLayout.LayoutParams(-2,-2); aiParams.rightMargin=Ui.dp(service,8);
                metaRow.addView(ai,aiParams);
            }
            TextView counter=Ui.text(service,"还需 "+challenge.remaining+" 道",13,Ui.MINT); remainingLabel=counter;
            counter.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); counter.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            metaRow.addView(counter,new LinearLayout.LayoutParams(-2,-2));
            TextView countdown=Ui.text(service,challenge.untimed?"本题不计时":challenge.timedOut?"超时 · 已暂停":challenge.timerPaused?"计时已暂停":"本题 "+formatRemaining(challenge.deadlineAtMillis-System.currentTimeMillis()),13,Ui.MINT);
            countdown.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL); countdown.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
            LinearLayout.LayoutParams countdownParams=new LinearLayout.LayoutParams(-2,-2); countdownParams.leftMargin=Ui.dp(service,10);
            metaRow.addView(countdown,countdownParams); Ui.add(card,metaRow,10);
            TextView title = Ui.text(service,MathText.format(question.title),23,Ui.INK); title.setGravity(Gravity.CENTER); title.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(card,title,6);
            if(modelLoading) Ui.add(card,Ui.text(service,"正在生成本题演示…",13,Ui.MUTED),8);
            else if(!modelError.isEmpty()) Ui.add(card,Ui.text(service,modelError+"，点击右上角重试",13,Ui.MUTED),8);
            else if(modelOpen && modelData!=null) {
                LinearLayout display=Ui.column(service); display.setPadding(Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,8),Ui.dp(service,8));
                display.setBackground(Ui.shape(service,android.graphics.Color.rgb(245,249,253),0));
                boolean linear="linear".equals(modelData.optString("type"));
                String parameter=modelData.optString("parameterName","");
                TextView caption=Ui.text(service,linear?parameter+" = 0.00 · 拖动观察直线变化":"拖动进度，观察图形变化",13,Ui.MINT);
                Ui.add(display,caption,0);
                MathModelView model=new MathModelView(service,modelData);
                display.addView(model,new LinearLayout.LayoutParams(-1,Ui.dp(service,190)));
                SeekBar slider=new SeekBar(service); slider.setMax(400);
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar bar,int value,boolean fromUser) {
                        model.setProgress(value/400f);
                        if(linear) caption.setText(String.format(java.util.Locale.ROOT,"%s = %.2f · 拖动观察直线变化",parameter,-10+value/20.0));
                    }
                    @Override public void onStartTrackingTouch(SeekBar bar) { model.stopPlayback(); }
                    @Override public void onStopTrackingTouch(SeekBar bar) { }
                });
                display.addView(slider,new LinearLayout.LayoutParams(-1,Ui.dp(service,40)));
                if(linear) slider.setProgress(200); else model.play(slider);
                Ui.add(card,display,8);
            }
            android.util.DisplayMetrics metrics=service.getResources().getDisplayMetrics();
            android.graphics.Paint choicePaint=new android.graphics.Paint(); choicePaint.setTextSize(15f*metrics.scaledDensity);
            float maxChoiceWidth=0f;
            for(String choice:question.choices) maxChoiceWidth=Math.max(maxChoiceWidth,choicePaint.measureText(MathText.format(choice)));
            int screenWidthDp=Math.round(metrics.widthPixels/metrics.density);
            int cardWidthDp=Math.min(screenWidthDp-16,680), contentWidthDp=cardWidthDp-24;
            int contentWidthPx=Ui.dp(service,contentWidthDp), buttonChrome=Ui.dp(service,24);
            int columns=maxChoiceWidth+buttonChrome<=contentWidthPx/2f?Math.min(2,question.choices.size()):1;
            if(contentWidthDp>=540 && maxChoiceWidth+buttonChrome<=contentWidthPx/4f) columns=Math.min(4,question.choices.size());
            GridLayout options=new GridLayout(service); options.setColumnCount(columns); options.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            boolean hasHint=!challenge.hint.isEmpty();
            int hintAccent=hasHint?android.graphics.Color.rgb(168,91,16):Ui.MINT;
            int hintBackground=hasHint?android.graphics.Color.rgb(255,247,232):android.graphics.Color.rgb(239,244,253);
            LinearLayout explanation=Ui.column(service); explanation.setPadding(Ui.dp(service,10),Ui.dp(service,7),Ui.dp(service,10),Ui.dp(service,7));
            explanation.setBackground(Ui.shape(service,hintBackground,0));
            if(hasHint) {
                TextView knowledgeTitle=Ui.text(service,"基础知识",13,Ui.MINT);
                knowledgeTitle.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(explanation,knowledgeTitle,0);
                String content=question.basicKnowledge.isEmpty()?"本题暂无整理好的基础知识。":MathText.format(question.basicKnowledge);
                Ui.add(explanation,Ui.text(service,content,14,android.graphics.Color.rgb(65,77,100)),3);
            }
            TextView explanationTitle=Ui.text(service,hasHint?"解题提示":"答题说明",13,hintAccent);
            explanationTitle.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); Ui.add(explanation,explanationTitle,hasHint?9:0);
            TextView explanationBody=Ui.text(service,hasHint?MathText.format(challenge.hint):challenge.timedOut?"时间到，已追加一道题。请继续完成本题。":"选择一个答案，答对后继续使用。",14,
                hasHint?android.graphics.Color.rgb(105,70,34):android.graphics.Color.rgb(65,77,100));
            Ui.add(explanation,explanationBody,3);
            TextView explanationFoot=hasHint?Ui.text(service,"已追加一道 · 还需答对 "+challenge.remaining+" 道",12,Ui.MUTED):null;
            Button first = null;
            for (int i=0;i<question.choices.size();i++) {
                final int choiceIndex = i;
                Button answer = Ui.button(service,MathText.format(question.choices.get(i)),false,v -> {
                    try {
                        QuestionBank.AnswerResult result = bank.answer(choiceIndex,state.level());
                        if (result.timedOut) {
                            hide(); show();
                            Toast.makeText(service,"时间到，已追加一道；请继续完成本题",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (result.correct && result.complete) {
                            boolean testing=state.quizTest();
                            boolean blacklisted=bank.isBlacklistSession(); String blockedPackage=bank.blacklistPackage();
                            boolean released = state.locked() ? state.unlock() : blacklisted && state.allowBlacklistApp(blockedPackage);
                            if(released && blacklisted && !state.isBlacklistAllowed(blockedPackage))
                                released=state.allowBlacklistApp(blockedPackage);
                            if (released) {
                                try { bank.finishSession(); } catch (Exception ignored) { bank.clearSession(); }
                                service.released(); hide();
                                Toast.makeText(service,blacklisted?"挑战完成，当前应用已放行":testing?"测试结束":"答对了，开启新一轮！",Toast.LENGTH_SHORT).show();
                            } else {
                                explanationTitle.setText("保存失败"); explanationTitle.setTextColor(android.graphics.Color.rgb(166,54,44));
                                explanationBody.setText("答题进度未保存，请由家长检查设备存储。");
                                explanationBody.setTextColor(android.graphics.Color.rgb(120,54,45));
                            }
                        } else {
                            hide(); show();
                            if (result.correct) Toast.makeText(service,"答对了，继续下一题",Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        explanationTitle.setText("保存失败"); explanationTitle.setTextColor(android.graphics.Color.rgb(166,54,44));
                        explanationBody.setText("题目进度未保存，请由家长检查设备存储。");
                        explanationBody.setTextColor(android.graphics.Color.rgb(120,54,45));
                    }
                });
                answer.setTextSize(15); answer.setMinHeight(Ui.dp(service,48));
                answer.setPadding(Ui.dp(service,8),Ui.dp(service,7),Ui.dp(service,8),Ui.dp(service,7));
                if(challenge.tried.contains(i)) {
                    answer.setEnabled(false);
                    answer.setBackground(Ui.shape(service,android.graphics.Color.rgb(255,239,235),android.graphics.Color.rgb(231,145,126)));
                    answer.setTextColor(android.graphics.Color.rgb(153,64,48));
                    answer.setContentDescription(MathText.format(question.choices.get(i))+"，已答错");
                }
                GridLayout.LayoutParams option=new GridLayout.LayoutParams(GridLayout.spec(i/columns),GridLayout.spec(i%columns,1,1f));
                option.width=0; option.height=-2;
                int gap=Ui.dp(service,3); option.setMargins(gap,gap,gap,gap); options.addView(answer,option);
                if (first == null && answer.isEnabled()) first = answer;
            }
            Ui.add(card,options,8);
            if(hasHint) Ui.add(explanation,explanationFoot,3);
            Ui.add(card,explanation,8);
            scroll.addView(outer); mountFrame(frame,"洋哥做题了 · 答题");
            try { bank.markShown(question.id); } catch(Exception e) { state.error("无法保存出题记录"); }
            timerLabel=countdown; startCountdown(bank,countdown,challenge.deadlineAtMillis,challenge.timerPaused,challenge.timedOut,challenge.untimed,state.level());
            if (first != null) first.requestFocus(); pauseMedia();
            state.error("");
        } catch (RuntimeException e) {
            if(!refreshingInPlace) root=null;
            else if(root!=null) scheduleWaitingRefresh();
            state.error("无法显示答题窗，请检查悬浮窗权限：" + e.getClass().getSimpleName());
        }
    }
    private void mountFrame(FrameLayout frame,String title) {
        if(refreshingInPlace && root instanceof FrameLayout) {
            // 保持系统悬浮窗不关闭，只替换内容，避免露出桌面。
            FrameLayout container=(FrameLayout)root;
            container.removeAllViews(); container.addView(frame,new FrameLayout.LayoutParams(-1,-1));
            frame.requestFocus(); return;
        }
        frame.setBackgroundColor(Ui.BG); root=frame;
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
        params.gravity=Gravity.TOP|Gravity.START; params.setTitle(title); windows.addView(root,params);
    }
    private void scheduleWaitingRefresh() {
        if(waitingRefresh!=null) timerHandler.removeCallbacks(waitingRefresh);
        final View expected=root;
        waitingRefresh=() -> { waitingRefresh=null; if(root==expected && root!=null) refreshWaiting(); };
        timerHandler.postDelayed(waitingRefresh,5000);
    }
    private void refreshWaiting() {
        if(root==null) return;
        if(waitingRefresh!=null) timerHandler.removeCallbacks(waitingRefresh);
        waitingRefresh=null; refreshingInPlace=true;
        try { show(); } finally { refreshingInPlace=false; }
    }
    public void refreshRemaining() {
        if(remainingLabel!=null) remainingLabel.setText("还需 "+QuestionBank.get(service).currentRemaining()+" 道");
    }
    public void hide() {
        if(waitingRefresh!=null) timerHandler.removeCallbacks(waitingRefresh);
        waitingRefresh=null;
        remainingLabel=null; stopCountdown();
        if (root != null) { try { windows.removeView(root); } catch (RuntimeException ignored) { } root = null; }
        if (focus != null) {
            try { ((AudioManager)service.getSystemService(Context.AUDIO_SERVICE)).abandonAudioFocusRequest(focus); } catch (RuntimeException ignored) { }
            focus = null;
        }
    }
    private void startCountdown(QuestionBank bank,TextView label,long deadlineAt,boolean paused,boolean timedOut,boolean untimed,int fallbackLevel) {
        stopCountdown(); timerLabel=label;
        if(untimed) { label.setText("本题不计时"); label.setTextColor(Ui.MINT); return; }
        if(paused) { label.setText(timedOut?"超时 · 已暂停":"计时已暂停"); label.setTextColor(Ui.MINT); return; }
        timerTick=new Runnable() {
            @Override public void run() {
                if(root==null || timerLabel!=label) return;
                long now=System.currentTimeMillis();
                long remaining=deadlineAt-now;
                if(remaining<=0) {
                    try {
                        QuestionBank.AnswerResult result=bank.checkTimeout(fallbackLevel);
                        if(result.timedOut) {
                            hide(); show();
                            Toast.makeText(service,"时间到，已追加一道；请继续完成本题",Toast.LENGTH_SHORT).show();
                            return;
                        }
                        label.setText("本题时间到"); return;
                    } catch(Exception e) { state.error("倒计时更新失败，请由家长检查设备存储"); }
                }
                label.setText("本题 "+formatRemaining(Math.max(0,remaining)));
                label.setTextColor(remaining<=10_000?android.graphics.Color.rgb(177,67,48):Ui.MINT);
                timerHandler.postDelayed(this,1000);
            }
        };
        timerHandler.post(timerTick);
    }

    private void requestModel(Question question) {
        if(!ApiKeyVault.isConfigured(service)) { modelError="请由家长设置百炼 API Key"; modelOpen=true; hide(); show(); return; }
        modelLoading=true; modelOpen=true; modelError=""; final int request=++modelRequestSerial;
        hide(); show();
        new Thread(() -> {
            JSONObject result=null; String error="";
            try { result=QwenClient.generateModel(ApiKeyVault.read(service),question); }
            catch(Exception e) { error=e.getMessage()==null?"演示生成失败":e.getMessage(); }
            final JSONObject generated=result; final String failure=error;
            timerHandler.post(() -> {
                if(request!=modelRequestSerial || !question.id.equals(modelQuestionId)) return;
                modelLoading=false; modelData=generated; modelError=failure;
                if(root!=null) { hide(); show(); }
            });
        },"question-model").start();
    }
    private void stopCountdown() {
        if(timerTick!=null) timerHandler.removeCallbacks(timerTick);
        timerTick=null; timerLabel=null;
    }
    private String formatRemaining(long remainingMillis) {
        long seconds=Math.max(0,(remainingMillis+999)/1000), hours=seconds/3600, minutes=(seconds%3600)/60, rest=seconds%60;
        return hours>0?String.format(java.util.Locale.ROOT,"%d:%02d:%02d",hours,minutes,rest)
            :String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,rest);
    }
    private void openParentExit() {
        try {
            service.startActivity(new Intent(service,MainActivity.class).putExtra("gesture_exit",true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            hide();
        } catch (RuntimeException e) { Toast.makeText(service,"无法打开家长验证，请由家长网页放行",Toast.LENGTH_LONG).show(); }
    }
    private void openBlacklistExit(String pkg) {
        try {
            service.startActivity(new Intent(service,MainActivity.class).putExtra("blacklist_exit",true)
                .putExtra("blacklist_package",pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
            hide();
        } catch (RuntimeException e) { Toast.makeText(service,"无法打开家长验证，请由家长网页放行",Toast.LENGTH_LONG).show(); }
    }
    private void pauseMedia() {
        lastPause = SystemClock.elapsedRealtime();
        try {
            if (focus == null) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setOnAudioFocusChangeListener(change -> { }).build();
                ((AudioManager)service.getSystemService(Context.AUDIO_SERVICE)).requestAudioFocus(focus);
            }
        } catch (RuntimeException ignored) { }
        try {
            MediaSessionManager sessions = (MediaSessionManager)service.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController controller : sessions.getActiveSessions(new ComponentName(service,MediaAccess.class))) {
                if (!controller.getPackageName().equals(service.getPackageName())) controller.getTransportControls().pause();
            }
        } catch (SecurityException ignored) { /* 家长未授予可选通知访问权限时，仅尝试音频焦点暂停。 */ }
    }
}
