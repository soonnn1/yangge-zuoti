package cn.tan.watchgate;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.OutputStream;
import java.util.List;

/** 桌面拍题入口：实时取景、相册选择和不提供最终答案的分步讲解。 */
@SuppressWarnings("deprecation")
public final class PhotoTutorActivity extends Activity implements TextureView.SurfaceTextureListener {
    private static final int CHOOSE_IMAGE=7102,CAMERA_PERMISSION=7103;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private PhotoTutorSession session;
    private TextureView preview;
    private ImageView selectedPreview;
    private TextView cameraMessage,status;
    private Button gallery,confirm,switchCamera;
    private LinearLayout explanation;
    private Camera camera;
    private Camera.Size previewSize;
    private Uri selectedImage;
    private Bitmap thumbnail;
    private boolean active,previewReady,taking,waitingForPicture,thumbnailLoading,permissionRequested;
    private int displayOrientation,thumbnailGeneration,captureGeneration;
    private String fingerprint="";
    private final Runnable tick=new Runnable(){public void run(){update();if(active)handler.postDelayed(this,500);}};

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);session=PhotoTutorSession.get(this);
        if(saved!=null){String uri=saved.getString("selectedImage","");if(!uri.isEmpty())selectedImage=Uri.parse(uri);permissionRequested=saved.getBoolean("permissionRequested",false);}
        if(GateState.get(this).enabled()&&!GuardService.isRunning()){
            GuardService.start(this);Toast.makeText(this,"正在恢复管控，请稍后重新打开拍题解析",Toast.LENGTH_LONG).show();finish();return;
        }
        if(!PhotoTutorAccess.allowed(this)){Toast.makeText(this,"答题锁定或刷题期间不能使用拍题讲解",Toast.LENGTH_LONG).show();finish();return;}
        createPage();
        if(selectedImage!=null)showSelectedImage(selectedImage,false);
    }
    @Override protected void onResume(){
        super.onResume();if(isFinishing())return;
        if(!PhotoTutorAccess.allowed(this)){session.cancelForRestriction();finish();return;}
        active=true;
        if(selectedImage==null){
            if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera();
            else if(!permissionRequested){permissionRequested=true;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);}
            else showCameraMessage("相机未授权，点此授权或从相册选择",true);
        }
        handler.removeCallbacks(tick);handler.post(tick);
    }
    @Override protected void onPause(){active=false;handler.removeCallbacks(tick);if(waitingForPicture){captureGeneration++;waitingForPicture=false;taking=false;}closeCamera();super.onPause();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);thumbnailGeneration++;closeCamera();if(thumbnail!=null){selectedPreview.setImageDrawable(null);thumbnail.recycle();thumbnail=null;}super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out){if(selectedImage!=null)out.putString("selectedImage",selectedImage.toString());out.putBoolean("permissionRequested",permissionRequested);super.onSaveInstanceState(out);}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==CAMERA_PERMISSION){if(results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED){if(active&&selectedImage==null)openCamera();}else showCameraMessage("相机未授权，点此授权或从相册选择",true);updateButtons();}
    }
    private void createPage(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Ui.BG);
        LinearLayout outer=Ui.column(this);outer.setGravity(Gravity.TOP|Gravity.CENTER_HORIZONTAL);outer.setPadding(Ui.dp(this,10),Ui.dp(this,8),Ui.dp(this,10),Ui.dp(this,12));
        LinearLayout body=Ui.column(this);int width=Math.min(getResources().getDisplayMetrics().widthPixels-Ui.dp(this,20),Ui.dp(this,720));
        outer.addView(body,new LinearLayout.LayoutParams(width,-2));scroll.addView(outer);setContentView(scroll);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=Ui.text(this,"拍题解析",20,Ui.INK);title.setTypeface(null,android.graphics.Typeface.BOLD);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button close=Ui.button(this,"关闭",false,v->finish());heading.addView(close,new LinearLayout.LayoutParams(-2,Ui.dp(this,48)));Ui.add(body,heading,0);
        FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(24,30,38));
        int screenHeightDp=Math.round(getResources().getDisplayMetrics().heightPixels/getResources().getDisplayMetrics().density);
        int previewHeight=Math.max(180,Math.min(320,Math.round(screenHeightDp*.36f)));
        Ui.add(body,frame,6);frame.getLayoutParams().height=Ui.dp(this,previewHeight);
        preview=new TextureView(this);preview.setSurfaceTextureListener(this);frame.addView(preview,new FrameLayout.LayoutParams(-1,-1));
        selectedPreview=new ImageView(this);selectedPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);selectedPreview.setVisibility(View.GONE);frame.addView(selectedPreview,new FrameLayout.LayoutParams(-1,-1));
        cameraMessage=Ui.text(this,"正在打开相机…",15,Color.WHITE);cameraMessage.setGravity(Gravity.CENTER);cameraMessage.setOnClickListener(v->requestCamera());frame.addView(cameraMessage,new FrameLayout.LayoutParams(-1,-1));
        switchCamera=Ui.button(this,"切回相机",false,v->useCamera());switchCamera.setVisibility(View.GONE);
        FrameLayout.LayoutParams switchParams=new FrameLayout.LayoutParams(-2,Ui.dp(this,44),Gravity.BOTTOM|Gravity.END);switchParams.setMargins(0,0,Ui.dp(this,8),Ui.dp(this,8));frame.addView(switchCamera,switchParams);
        LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);
        gallery=Ui.button(this,"从相册选择",false,v->chooseImage());confirm=Ui.button(this,"确认分析",true,v->confirmImage());
        LinearLayout.LayoutParams left=new LinearLayout.LayoutParams(0,Ui.dp(this,48),1);left.rightMargin=Ui.dp(this,4);
        LinearLayout.LayoutParams right=new LinearLayout.LayoutParams(0,Ui.dp(this,48),1);right.leftMargin=Ui.dp(this,4);
        actions.addView(gallery,left);actions.addView(confirm,right);Ui.add(body,actions,8);
        status=Ui.text(this,"对准一道题，点击确认分析。",14,Ui.MUTED);Ui.add(body,status,8);
        explanation=Ui.column(this);Ui.add(body,explanation,4);
        updateButtons();
    }
    private void update(){
        if(!PhotoTutorAccess.allowed(this)){session.cancelForRestriction();Toast.makeText(this,"答题期间不能使用拍题讲解",Toast.LENGTH_SHORT).show();finish();return;}
        try{
            JSONObject state=session.snapshot();String next=state.toString()+":"+taking+":"+thumbnailLoading+":"+(selectedImage!=null)+":"+previewReady;
            if(next.equals(fingerprint))return;fingerprint=next;renderExplanation(state);updateButtons();
        }catch(Exception e){status.setText("解析状态暂不可用，请重试。");}
    }
    private void updateButtons(){
        if(confirm==null)return;
        boolean busy=taking||thumbnailLoading;
        try{busy=busy||"loading".equals(session.snapshot().optString("state"));}catch(Exception ignored){}
        gallery.setEnabled(!busy);switchCamera.setEnabled(!busy);
        confirm.setEnabled(!busy&&(selectedImage!=null||previewReady));
        confirm.setText(taking?"正在拍摄…":busy?"正在分析…":"确认分析");
    }
    private void renderExplanation(JSONObject state) throws Exception {
        explanation.removeAllViews();status.setTextColor(Ui.MUTED);String phase=state.optString("state");
        if(taking){status.setText("正在拍摄题目…");return;}
        if(thumbnailLoading){status.setText("正在读取相册图片…");return;}
        if("loading".equals(phase)){status.setText(state.optString("message","正在分析…"));ProgressBar progress=new ProgressBar(this);Ui.add(explanation,progress,6);return;}
        if("error".equals(phase)){status.setText(state.optString("message","解析失败，请重试"));status.setTextColor(Color.rgb(160,43,39));return;}
        status.setTextColor(Ui.MUTED);
        JSONObject result=state.optJSONObject("result");
        if(result==null){status.setText(selectedImage==null?"对准一道题，点击确认分析。":"已选择相册图片，点击确认分析。");return;}
        if(!result.optBoolean("canRead",false)){status.setText(result.optString("reason","请重新选择清晰的题目图片。"));return;}
        status.setText("识别完成，下面是解析过程。");
        LinearLayout question=card(explanation);Ui.add(question,Ui.text(this,"识别出的题目",14,Ui.MUTED),0);Ui.add(question,Ui.text(this,MathText.format(result.optString("question")),17,Ui.INK),6);
        JSONArray steps=result.getJSONArray("steps");
        for(int i=0;i<steps.length();i++){
            LinearLayout box=card(explanation);Ui.add(box,Ui.text(this,"第 "+(i+1)+" 步",15,Ui.MINT),0);Ui.add(box,Ui.text(this,MathText.format(steps.getString(i)),16,Ui.INK),5);
        }
    }
    private LinearLayout card(LinearLayout parent){LinearLayout box=Ui.column(this);box.setPadding(Ui.dp(this,12),Ui.dp(this,10),Ui.dp(this,12),Ui.dp(this,10));box.setBackground(Ui.shape(this,Ui.CARD,0));Ui.add(parent,box,8);return box;}

    private void requestCamera(){
        if(selectedImage!=null)return;
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera();
        else{permissionRequested=true;requestPermissions(new String[]{Manifest.permission.CAMERA},CAMERA_PERMISSION);}
    }
    private void showCameraMessage(String message,boolean visible){if(cameraMessage!=null){cameraMessage.setText(message);cameraMessage.setVisibility(visible?View.VISIBLE:View.GONE);}}
    private void openCamera(){
        if(!active||selectedImage!=null||camera!=null||preview==null||!preview.isAvailable()||checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)return;
        if(!PhotoTutorAccess.allowed(this)){update();return;}
        try{
            int count=Camera.getNumberOfCameras();if(count==0)throw new IllegalStateException("设备没有可用相机");
            int index=0;Camera.CameraInfo info=new Camera.CameraInfo();
            for(int i=0;i<count;i++){Camera.getCameraInfo(i,info);if(info.facing==Camera.CameraInfo.CAMERA_FACING_BACK){index=i;break;}}
            Camera.getCameraInfo(index,info);camera=Camera.open(index);
            int rotation=getWindowManager().getDefaultDisplay().getRotation(),degrees=rotation==Surface.ROTATION_90?90:rotation==Surface.ROTATION_180?180:rotation==Surface.ROTATION_270?270:0;
            int jpegRotation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(info.orientation+degrees)%360:(info.orientation-degrees+360)%360;
            displayOrientation=info.facing==Camera.CameraInfo.CAMERA_FACING_FRONT?(360-jpegRotation)%360:jpegRotation;
            camera.setDisplayOrientation(displayOrientation);
            Camera.Parameters params=camera.getParameters();
            previewSize=chooseSize(params.getSupportedPreviewSizes(),1280);if(previewSize!=null)params.setPreviewSize(previewSize.width,previewSize.height);
            Camera.Size photo=chooseSize(params.getSupportedPictureSizes(),2048);if(photo!=null)params.setPictureSize(photo.width,photo.height);
            params.setRotation(jpegRotation);
            List<String> focus=params.getSupportedFocusModes();if(focus!=null&&focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(params);camera.setPreviewTexture(preview.getSurfaceTexture());camera.startPreview();previewReady=true;
            showCameraMessage("",false);fitPreview();fingerprint="";updateButtons();
        }catch(Exception e){closeCamera();showCameraMessage("相机暂不可用，点此重试或从相册选择",true);fingerprint="";updateButtons();}
    }
    private Camera.Size chooseSize(List<Camera.Size> sizes,int target){
        if(sizes==null||sizes.isEmpty())return null;Camera.Size best=null;double score=Double.MAX_VALUE;
        for(Camera.Size size:sizes){int edge=Math.max(size.width,size.height);double aspect=size.width/(double)size.height;
            double candidate=Math.abs(aspect-4d/3d)*5+Math.abs(edge-target)/(double)target+(edge>target?1:0);
            if(candidate<score){score=candidate;best=size;}}
        return best;
    }
    private void fitPreview(){
        if(previewSize==null||preview==null||preview.getWidth()==0||preview.getHeight()==0)return;
        float width=preview.getWidth(),height=preview.getHeight();
        float aspect=displayOrientation%180==0?previewSize.width/(float)previewSize.height:previewSize.height/(float)previewSize.width;
        float viewAspect=width/height,sx=1,sy=1;if(viewAspect>aspect)sx=aspect/viewAspect;else sy=viewAspect/aspect;
        Matrix matrix=new Matrix();matrix.setScale(sx,sy,width/2,height/2);preview.setTransform(matrix);
    }
    private void closeCamera(){
        previewReady=false;previewSize=null;if(camera!=null){try{camera.stopPreview();}catch(Exception ignored){}try{camera.release();}catch(Exception ignored){}camera=null;}
    }
    @Override public void onSurfaceTextureAvailable(SurfaceTexture surface,int width,int height){openCamera();}
    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture surface,int width,int height){fitPreview();}
    @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture surface){closeCamera();return true;}
    @Override public void onSurfaceTextureUpdated(SurfaceTexture surface){}

    private void chooseImage(){
        if(!PhotoTutorAccess.allowed(this)){update();return;}
        try{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(intent,CHOOSE_IMAGE);}
        catch(Exception e){Toast.makeText(this,"无法打开相册",Toast.LENGTH_LONG).show();}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=CHOOSE_IMAGE||result!=RESULT_OK||data==null||data.getData()==null)return;
        if(!PhotoTutorAccess.allowed(this)){session.cancelForRestriction();finish();return;}
        showSelectedImage(data.getData(),true);
    }
    private void showSelectedImage(Uri uri,boolean clearResult){
        if(clearResult)session.reset();selectedImage=uri;thumbnailLoading=true;int generation=++thumbnailGeneration;closeCamera();fingerprint="";updateButtons();
        new Thread(()->{
            Bitmap loaded=null;String error=null;
            try{loaded=PhotoTutorSession.readBitmap(getApplicationContext(),uri,1024);}catch(OutOfMemoryError e){error="图片太大，请重新选择";}catch(Exception e){error="图片无法读取，请重新选择";}
            final Bitmap image=loaded;final String failure=error;
            runOnUiThread(()->{
                if(isFinishing()||isDestroyed()||generation!=thumbnailGeneration){if(image!=null)image.recycle();return;}
                thumbnailLoading=false;
                if(failure!=null){selectedImage=null;selectedPreview.setVisibility(View.GONE);switchCamera.setVisibility(View.GONE);Toast.makeText(this,failure,Toast.LENGTH_LONG).show();if(active)openCamera();}
                else{Bitmap old=thumbnail;thumbnail=image;selectedPreview.setImageBitmap(image);selectedPreview.setVisibility(View.VISIBLE);switchCamera.setVisibility(View.VISIBLE);showCameraMessage("",false);if(old!=null&&old!=image)old.recycle();}
                fingerprint="";updateButtons();if(active)update();
            });
        },"photo-tutor-preview").start();
    }
    private void useCamera(){
        thumbnailGeneration++;thumbnailLoading=false;selectedImage=null;selectedPreview.setImageDrawable(null);selectedPreview.setVisibility(View.GONE);switchCamera.setVisibility(View.GONE);
        if(thumbnail!=null){thumbnail.recycle();thumbnail=null;}session.reset();fingerprint="";
        if(checkSelfPermission(Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED)openCamera();else showCameraMessage("点此授权相机或从相册选择",true);
        updateButtons();update();
    }
    private void confirmImage(){
        if(!PhotoTutorAccess.allowed(this)){update();return;}
        if(!ApiKeyVault.isConfigured(this)){Toast.makeText(this,"请让家长先配置千问 API Key",Toast.LENGTH_LONG).show();return;}
        if(selectedImage!=null){beginAnalysis(selectedImage,false);return;}
        if(camera==null||!previewReady||taking){Toast.makeText(this,"请等待相机就绪",Toast.LENGTH_SHORT).show();return;}
        session.reset();taking=true;waitingForPicture=true;final int generation=++captureGeneration;previewReady=false;fingerprint="";updateButtons();update();
        try{
            camera.takePicture(null,null,(bytes,source)->{
                if(generation!=captureGeneration)return;waitingForPicture=false;
                try{if(active&&camera==source){source.startPreview();previewReady=true;}}catch(Exception ignored){closeCamera();}
                new Thread(()->saveCapture(bytes),"photo-tutor-capture").start();
            });
        }catch(Exception e){taking=false;waitingForPicture=false;closeCamera();openCamera();Toast.makeText(this,"拍摄失败，请重试",Toast.LENGTH_LONG).show();fingerprint="";update();}
    }
    private void saveCapture(byte[] bytes){
        Uri uri=null;
        try{
            if(bytes==null||bytes.length==0)throw new IllegalStateException("相机没有返回图片");
            uri=PhotoCaptureProvider.create(getApplicationContext());
            try(OutputStream out=getContentResolver().openOutputStream(uri,"w")){if(out==null)throw new IllegalStateException("无法保存拍摄结果");out.write(bytes);}
            if(!PhotoTutorAccess.allowed(getApplicationContext()))throw new IllegalStateException("答题期间不能使用拍题讲解");
            session.start(uri,true);uri=null;
            runOnUiThread(()->{taking=false;fingerprint="";if(active)update();});
        }catch(Exception e){PhotoCaptureProvider.remove(getApplicationContext(),uri);String message=e.getMessage()==null?"拍摄失败，请重试":e.getMessage();runOnUiThread(()->{taking=false;fingerprint="";if(active){Toast.makeText(this,message,Toast.LENGTH_LONG).show();update();}});}
    }
    private void beginAnalysis(Uri image,boolean temporary){
        try{session.start(image,temporary);fingerprint="";update();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
