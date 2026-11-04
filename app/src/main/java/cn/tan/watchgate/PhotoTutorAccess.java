package cn.tan.watchgate;

import android.content.Context;

/** 拍题只在普通使用时间可打开，不能穿过现有答题锁定或刷题模式。 */
final class PhotoTutorAccess {
    static boolean allowed(Context context){
        GateState state=GateState.get(context);
        if(PracticeBank.get(context).enabled()||GuardService.isQuizBlocking())return false;
        if(state.enabled()&&!GuardService.isRunning())return false;
        if(ScheduleManager.isPaused(context,System.currentTimeMillis()))return true;
        if(!state.enabled())return !state.quizTest();
        QuestionBank bank=QuestionBank.get(context);
        if(bank.loanDebtDue())return false;
        if(bank.loanRemainingMs()>0)return true;
        return !state.locked();
    }
}
