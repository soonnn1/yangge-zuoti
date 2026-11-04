package cn.tan.watchgate;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** 仅向同签名的洋哥拍题 App 暴露拍题所需的 Key 与管控状态。 */
public final class PhotoTutorBridgeProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}

    @Override public Bundle call(String method,String arg,Bundle extras){
        if(!"photoTutorState".equals(method))return Bundle.EMPTY;
        Context context=getContext();
        if(context==null)return Bundle.EMPTY;
        Bundle result=new Bundle();
        result.putBoolean("allowed",PhotoTutorAccess.allowed(context));
        try {
            result.putString("apiKey",ApiKeyVault.isConfigured(context)?ApiKeyVault.read(context):"");
        } catch(Exception ignored) {
            result.putString("apiKey","");
        }
        return result;
    }

    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){return null;}
    @Override public String getType(Uri uri){return null;}
    @Override public Uri insert(Uri uri,ContentValues values){return null;}
    @Override public int delete(Uri uri,String selection,String[] selectionArgs){return 0;}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] selectionArgs){return 0;}
}
