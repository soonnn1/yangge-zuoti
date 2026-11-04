package cn.tan.watchgate;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;
import android.widget.SeekBar;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** 按校验过的题目数据绘图，拖动进度观察变化，不执行 AI 代码。 */
public final class MathModelView extends View {
    private static final int BLUE=Color.rgb(60,113,166), ORANGE=Color.rgb(219,137,55), PALE=Color.rgb(219,232,245), INK=Color.rgb(73,91,110);
    private final JSONObject spec;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private float progress;
    private ValueAnimator animator;
    public MathModelView(Context context,JSONObject spec) { super(context); this.spec=spec; setContentDescription("可操作的数学模型"); }
    public void setProgress(float value) { progress=Math.max(0,Math.min(1,value)); invalidate(); }
    public void stopPlayback() { if(animator!=null) { animator.cancel(); animator=null; } }
    public void play(SeekBar control) {
        stopPlayback(); animator=ValueAnimator.ofInt(0,control.getMax()); animator.setDuration(5000);
        animator.addUpdateListener(a -> control.setProgress((Integer)a.getAnimatedValue())); animator.start();
    }
    @Override protected void onDetachedFromWindow() { stopPlayback(); super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.save(); canvas.scale(getWidth()/1000f,getHeight()/500f);
        paint.setColor(Color.rgb(245,249,253)); paint.setStyle(Paint.Style.FILL); canvas.drawRoundRect(0,0,1000,500,20,20,paint);
        String type=spec.optString("type","");
        if(type.equals("fraction")) fraction(canvas);
        else if(type.equals("rectangle")) rectangle(canvas);
        else if(type.equals("groups")) groups(canvas);
        else if(type.equals("number_line")) numberLine(canvas);
        else if(type.equals("linear")) linear(canvas);
        canvas.restore();
    }
    private void text(Canvas c,String value,float x,float y,int color,float size) {
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setTextSize(size); c.drawText(value,x,y,paint);
    }
    private void fraction(Canvas c) {
        int parts=spec.optInt("parts"),selected=spec.optInt("selected"); float unit=840f/parts;
        text(c,"观察部分与整体",80,90,INK,32);
        for(int i=0;i<parts;i++) {
            float left=80+i*unit; paint.setStyle(Paint.Style.FILL); paint.setColor(PALE); c.drawRect(left+2,170,left+unit-2,300,paint);
            float filled=Math.max(0,Math.min(1,progress*selected-i));
            if(filled>0) { paint.setColor(BLUE); c.drawRect(left+2,170,left+2+(unit-4)*filled,300,paint); }
        }
    }
    private void rectangle(Canvas c) {
        int width=spec.optInt("width"),height=spec.optInt("height"); float cell=Math.min(700f/width,300f/height);
        float left=(1000-cell*width)/2,top=(500-cell*height)/2;
        text(c,"观察图形由多少单位小块组成",80,70,INK,30);
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            float visible=Math.max(0,Math.min(1,progress*width*height-(y*width+x)));
            paint.setStyle(Paint.Style.FILL); paint.setColor(visible>0?BLUE:PALE);
            c.drawRect(left+x*cell+1,top+y*cell+1,left+(x+1)*cell-1,top+(y+1)*cell-1,paint);
        }
    }
    private void groups(Canvas c) {
        int groups=spec.optInt("groups"),each=spec.optInt("each"),cols=Math.min(5,groups),rows=(groups+cols-1)/cols;
        float groupW=840f/cols,groupH=300f/rows;
        text(c,"观察每组和整体的关系",80,65,INK,30);
        for(int g=0;g<groups;g++) {
            float left=80+(g%cols)*groupW,top=95+(g/cols)*groupH;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(INK);
            c.drawRoundRect(left+3,top+3,left+groupW-6,top+groupH-6,12,12,paint);
            for(int j=0;j<each;j++) {
                int dotCols=Math.min(each,4),dotRows=(each+dotCols-1)/dotCols;
                float x=left+(j%dotCols+0.5f)*groupW/dotCols,y=top+(j/dotCols+0.5f)*groupH/dotRows;
                float radius=Math.min(13,Math.min(groupW/dotCols,groupH/dotRows)*0.24f);
                paint.setStyle(Paint.Style.FILL); paint.setColor(progress*groups*each>g*each+j?ORANGE:PALE); c.drawCircle(x,y,radius,paint);
            }
        }
    }
    private void numberLine(Canvas c) {
        int start=spec.optInt("start"),distance=spec.optInt("distance"),direction=spec.optInt("direction");
        text(c,"从题目给出的起点，观察移动",80,80,INK,30);
        float left=100,right=900,y=255; paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setColor(INK); c.drawLine(left,y,right,y,paint);
        float step=720f/(distance+2),origin=direction>0?left+step:right-step;
        for(int i=0;i<=distance;i++) { float x=origin+direction*i*step; c.drawLine(x,y-15,x,y+15,paint); }
        text(c,String.valueOf(start),origin-12,y+55,INK,30);
        paint.setStyle(Paint.Style.FILL); paint.setColor(ORANGE); c.drawCircle(origin+direction*distance*step*progress,y,17,paint);
    }
    private double coefficient(String name,double parameter) {
        Object value=spec.opt(name); return value instanceof String?parameter:((Number)value).doubleValue();
    }
    private void linear(Canvas c) {
        double px=spec.optDouble("pointX"),py=spec.optDouble("pointY");
        double range=Math.max(6,Math.ceil(Math.max(Math.abs(px),Math.abs(py)))+2),parameter=-10+20*progress;
        double a=coefficient("a",parameter),b=coefficient("b",parameter),constant=coefficient("c",parameter);
        float left=260,top=30,size=440,mid=left+size/2,centerY=top+size/2;
        paint.setColor(PALE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2);
        for(int i=0;i<=10;i++) { float u=i*size/10; c.drawLine(left+u,top,left+u,top+size,paint); c.drawLine(left,top+u,left+size,top+u,paint); }
        paint.setColor(INK); paint.setStrokeWidth(4); c.drawLine(left,centerY,left+size,centerY,paint); c.drawLine(mid,top,mid,top+size,paint);
        text(c,"x",left+size+7,centerY,INK,26); text(c,"y",mid+6,top+24,INK,26);
        List<double[]> ends=new ArrayList<>();
        if(Math.abs(b)>1e-9) { addPoint(ends,-range,(constant+a*range)/b,range); addPoint(ends,range,(constant-a*range)/b,range); }
        if(Math.abs(a)>1e-9) { addPoint(ends,(constant+b*range)/a,-range,range); addPoint(ends,(constant-b*range)/a,range,range); }
        if(ends.size()>=2) {
            paint.setColor(BLUE); paint.setStrokeWidth(6); double[] p=ends.get(0),q=ends.get(1);
            c.drawLine((float)(mid+p[0]/range*size/2),(float)(centerY-p[1]/range*size/2),(float)(mid+q[0]/range*size/2),(float)(centerY-q[1]/range*size/2),paint);
        } else text(c,"当前参数下没有可显示的直线",40,490,INK,26);
        paint.setStyle(Paint.Style.FILL); paint.setColor(ORANGE);
        c.drawCircle((float)(mid+px/range*size/2),(float)(centerY-py/range*size/2),11,paint);
        text(c,"已知点",720,65,ORANGE,27);
    }
    private void addPoint(List<double[]> points,double x,double y,double range) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>range+1e-7||Math.abs(y)>range+1e-7) return;
        for(double[] p:points) if(Math.abs(p[0]-x)<1e-7&&Math.abs(p[1]-y)<1e-7) return;
        points.add(new double[]{x,y});
    }
}
