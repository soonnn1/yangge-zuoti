package cn.tan.watchgate;

import android.content.Context;
import android.graphics.PixelFormat;
import android.provider.Settings;
import android.view.*;
import android.widget.*;

/** 独立检查悬浮窗通道，不修改计时和答题锁定状态。 */
public final class OverlayProbe {
    private static View visible;
    public static void show(Context caller) {
        if (!Settings.canDrawOverlays(caller)) {
            Toast.makeText(caller,"请先开启显示在其他应用上层权限",Toast.LENGTH_LONG).show(); return;
        }
        if (visible != null) return;
        Context context = new ContextThemeWrapper(caller.getApplicationContext(),R.style.AppTheme);
        WindowManager manager = (WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        LinearLayout panel=Ui.column(context); panel.setGravity(Gravity.CENTER);
        panel.setPadding(Ui.dp(context,28),Ui.dp(context,28),Ui.dp(context,28),Ui.dp(context,28)); panel.setBackgroundColor(Ui.BG);
        Runnable close = () -> {
            if(visible == panel) { try { manager.removeView(panel); } catch(RuntimeException ignored) { } visible=null; }
        };
        Ui.add(panel,Ui.text(context,"悬浮窗显示正常",28,Ui.MINT),0);
        Ui.add(panel,Ui.text(context,"能看到此页，说明基本悬浮窗通道正常。\n这次检查不扣时间、不解除答题锁定。",17,Ui.INK),18);
        Button done=Ui.button(context,"看到了 · 关闭",true,v -> close.run()); Ui.add(panel,done,24);
        try {
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
            params.setTitle("悬浮窗检查"); manager.addView(panel,params); visible=panel; done.requestFocus();
        } catch(RuntimeException e) {
            String error="悬浮窗检查失败："+e.getClass().getSimpleName(); GateState.get(caller).error(error);
            Toast.makeText(caller,error,Toast.LENGTH_LONG).show();
        }
    }
}
