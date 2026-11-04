package cn.tan.watchgate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.util.Base64;
import org.json.*;
import java.io.*;

/** 解析任务跨页面重建保留在进程内；进入受限答题时丢弃尚未完成的结果。 */
final class PhotoTutorSession {
    private static PhotoTutorSession instance;
    private final Context context;
    private long sequence;
    private String state="idle",message="";
    private JSONObject result;
    static synchronized PhotoTutorSession get(Context context){if(instance==null)instance=new PhotoTutorSession(context);return instance;}
    private PhotoTutorSession(Context context){this.context=context.getApplicationContext();}
    synchronized JSONObject snapshot() throws Exception {
        return new JSONObject().put("state",state).put("message",message).put("result",result==null?JSONObject.NULL:new JSONObject(result.toString()));
    }
    synchronized void reset(){sequence++;state="idle";message="";result=null;}
    synchronized void cancelForRestriction(){
        if(!"loading".equals(state))return;
        sequence++;state="error";message="答题期间不能使用拍题讲解，请完成后重新拍摄。";result=null;
    }
    synchronized void start(Uri image,boolean temporary) throws Exception {
        if(!PhotoTutorAccess.allowed(context))throw new IllegalStateException("答题锁定或刷题期间不能使用拍题讲解");
        if(!ApiKeyVault.isConfigured(context))throw new IllegalStateException("请让家长先配置千问 API Key");
        if("loading".equals(state))throw new IllegalStateException("正在解析，请稍候");
        final long request=++sequence;state="loading";message="正在识别题目并准备分步讲解…";result=null;
        new Thread(()->{
            try {
                String data=encodeImage(image);
                if(temporary)PhotoCaptureProvider.remove(context,image);
                if(!PhotoTutorAccess.allowed(context)){cancelForRestriction();return;}
                String apiKey=ApiKeyVault.read(context);
                JSONObject raw=QwenClient.explainPhoto(apiKey,data),clean=normalize(raw);
                if(clean.optBoolean("canRead",false)){
                    if(!PhotoTutorAccess.allowed(context)){cancelForRestriction();return;}
                    synchronized(this){if(request!=sequence)return;message="正在检查讲解是否包含答案…";}
                    if(QwenClient.photoGuidanceRevealsAnswer(apiKey,clean.getString("question"),clean.getJSONArray("steps")))throw new IllegalStateException("讲解可能包含答案，请重新分析");
                }
                synchronized(this){
                    if(request!=sequence)return;
                    if(!PhotoTutorAccess.allowed(context)){cancelForRestriction();return;}
                    result=clean;state="done";message="";
                }
            } catch(OutOfMemoryError e){fail(request,"图片太大，请拍摄清晰的单道题后重试");}
            catch(Exception e){fail(request,e.getMessage()==null?"解析失败，请检查网络后重试":e.getMessage());}
            finally{if(temporary)PhotoCaptureProvider.remove(context,image);}
        },"photo-tutor").start();
    }
    private synchronized void fail(long request,String error){if(request==sequence){state="error";message=error;result=null;}}
    private JSONObject normalize(JSONObject raw) throws Exception {
        if(!raw.optBoolean("canRead",false)){
            return new JSONObject().put("canRead",false).put("reason","题目不清楚或有多道题，请靠近拍摄并只保留一道题。");
        }
        String question=raw.optString("question","").trim();JSONArray original=raw.optJSONArray("steps"),steps=new JSONArray();
        if(question.isEmpty()||original==null)throw new IllegalStateException("讲解内容不完整，请重新拍摄");
        if(!raw.optString("answer","").trim().isEmpty())throw new IllegalStateException("讲解可能包含答案，请重新分析");
        for(int i=0;i<Math.min(original.length(),8);i++){String step=original.optString(i,"").trim();if(step.matches("(?s).*(最终答案|正确答案|答案是|故选|所以选|应选择)\\s*[:：为是]?\\s*[A-Da-d0-9一二三四五六七八九十].*"))throw new IllegalStateException("讲解可能包含答案，请重新分析");if(!step.isEmpty())steps.put(step.length()>2500?step.substring(0,2500):step);}
        if(steps.length()==0)throw new IllegalStateException("讲解步骤不完整，请重试");
        return new JSONObject().put("canRead",true).put("question",question).put("steps",steps);
    }
    // 统一按方向解码相册预览与发给千问的图片，限制像素避免大图耗尽内存。
    static Bitmap readBitmap(Context context,Uri uri,int maxEdge) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取图片");BitmapFactory.decodeStream(in,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0)throw new IOException("图片无法读取，请重新选择");
        int sample=1;while(Math.max(bounds.outWidth,bounds.outHeight)/sample>maxEdge)sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;
        Bitmap bitmap;
        try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in==null)throw new IOException("无法读取图片");bitmap=BitmapFactory.decodeStream(in,null,options);}
        if(bitmap==null)throw new IOException("图片无法读取，请重新选择");
        try {
            int orientation=ExifInterface.ORIENTATION_NORMAL;
            try(InputStream in=context.getContentResolver().openInputStream(uri)){if(in!=null)orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);}catch(Exception ignored){}
            Matrix matrix=new Matrix();
            switch(orientation){
                case ExifInterface.ORIENTATION_ROTATE_90:matrix.postRotate(90);break;
                case ExifInterface.ORIENTATION_ROTATE_180:matrix.postRotate(180);break;
                case ExifInterface.ORIENTATION_ROTATE_270:matrix.postRotate(270);break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.postScale(1,-1);break;
                case ExifInterface.ORIENTATION_TRANSPOSE:matrix.postRotate(90);matrix.postScale(-1,1);break;
                case ExifInterface.ORIENTATION_TRANSVERSE:matrix.postRotate(270);matrix.postScale(-1,1);break;
                default:break;
            }
            if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}}
            return bitmap;
        } catch(Exception|OutOfMemoryError e){bitmap.recycle();throw e;}
    }
    private String encodeImage(Uri uri) throws Exception {
        Bitmap bitmap=readBitmap(context,uri,2048);
        try {
            ByteArrayOutputStream out=new ByteArrayOutputStream();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,82,out))throw new IOException("图片处理失败");
            if(out.size()>4*1024*1024){out.reset();if(!bitmap.compress(Bitmap.CompressFormat.JPEG,60,out)||out.size()>4*1024*1024)throw new IOException("图片太大，请重新拍摄");}
            return "data:image/jpeg;base64,"+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
        } finally {bitmap.recycle();}
    }
}
