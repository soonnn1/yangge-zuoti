package cn.tan.yanggephoto;

import android.content.Context;

/** 主应用存在时服从其答题管控；主应用卸载后拍题 App 可独立运行。 */
final class PhotoTutorAccess {
    private PhotoTutorAccess() { }
    static boolean allowed(Context context){return PhotoTutorBridge.allowed(context);}
}
