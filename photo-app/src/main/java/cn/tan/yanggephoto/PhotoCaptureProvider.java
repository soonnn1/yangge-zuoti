package cn.tan.yanggephoto;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.UUID;

/** 只通过临时授权 URI 将本应用缓存中的拍摄文件交给系统相机。 */
public final class PhotoCaptureProvider extends ContentProvider {
    static Uri create(Context context) throws Exception {
        String name="capture_"+UUID.randomUUID().toString()+".jpg";
        if(!new File(context.getCacheDir(),name).createNewFile())throw new IllegalStateException("无法创建拍摄文件");
        return new Uri.Builder().scheme("content").authority(context.getPackageName()+".photo_tutor").appendPath(name).build();
    }
    private File file(Uri uri) throws FileNotFoundException {
        String name=uri.getLastPathSegment();
        if(uri.getPathSegments().size()!=1||name==null||!name.matches("capture_[a-f0-9-]{36}\\.jpg"))throw new FileNotFoundException("无效的拍摄文件");
        return new File(getContext().getCacheDir(),name);
    }
    static void remove(Context context,Uri uri){
        if(uri==null||!((context.getPackageName()+".photo_tutor").equals(uri.getAuthority())))return;
        String name=uri.getLastPathSegment();if(name!=null&&name.matches("capture_[a-f0-9-]{36}\\.jpg"))new File(context.getCacheDir(),name).delete();
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){return "image/jpeg";}
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode) throws FileNotFoundException {return ParcelFileDescriptor.open(file(uri),ParcelFileDescriptor.parseMode(mode));}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String sort){
        try{File image=file(uri);String[] cols=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(cols);MatrixCursor.RowBuilder row=cursor.newRow();
            for(String col:cols)row.add(OpenableColumns.DISPLAY_NAME.equals(col)?image.getName():OpenableColumns.SIZE.equals(col)?image.length():null);return cursor;
        }catch(FileNotFoundException e){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
    @Override public int delete(Uri uri,String selection,String[] args){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){return 0;}
}
