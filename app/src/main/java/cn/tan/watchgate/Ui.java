package cn.tan.watchgate;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(247,248,250), CARD = Color.WHITE, INK = Color.rgb(30,34,42), MUTED = Color.rgb(104,111,122), MINT = Color.rgb(61,98,188);
    public static int dp(Context c, float n) { return Math.round(n * c.getResources().getDisplayMetrics().density); }
    public static GradientDrawable shape(Context c, int color, int stroke) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,10));
        if (stroke != 0) d.setStroke(dp(c,2), stroke); return d;
    }
    public static LinearLayout column(Context c) {
        LinearLayout v = new LinearLayout(c); v.setOrientation(LinearLayout.VERTICAL); return v;
    }
    public static TextView text(Context c, String label, float size, int color) {
        TextView t = new TextView(c); t.setText(label); t.setTextSize(size); t.setTextColor(color);
        t.setLineSpacing(dp(c,1),1); return t;
    }
    public static void add(LinearLayout parent, View view, int gap) {
        // 全部原生页面统一收紧段落间距，小间距与按钮热区保持不变。
        int compactGap=gap>=8?Math.round(gap*0.7f):gap;
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1,-2); p.topMargin = dp(parent.getContext(),compactGap); parent.addView(view,p);
    }
    public static Button button(Context c, String label, boolean primary, View.OnClickListener click) {
        Button b = new Button(c); b.setText(label); b.setAllCaps(false); b.setTextSize(16); b.setGravity(Gravity.CENTER);
        b.setTypeface(Typeface.DEFAULT,Typeface.NORMAL); b.setMinHeight(dp(c,48)); b.setPadding(dp(c,10),dp(c,6),dp(c,10),dp(c,6));
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[]{android.R.attr.state_focused}, shape(c, Color.rgb(228,235,251),MINT));
        bg.addState(new int[]{android.R.attr.state_pressed}, shape(c, Color.rgb(228,235,251),MINT));
        bg.addState(new int[]{}, shape(c,primary ? MINT : CARD,primary ? 0 : Color.rgb(222,225,231))); b.setBackground(bg);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_pressed},new int[]{}},new int[]{INK,INK,primary ? BG : INK}));
        b.setFocusable(true); b.setOnClickListener(click); return b;
    }
    public static String duration(long ms) { long seconds = (Math.max(0,ms)+999)/1000; return String.format(java.util.Locale.ROOT,"%02d:%02d",seconds/60,seconds%60); }
}
