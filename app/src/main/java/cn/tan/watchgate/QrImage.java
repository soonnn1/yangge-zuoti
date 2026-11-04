package cn.tan.watchgate;

import android.graphics.Bitmap;
import android.graphics.Color;
import io.nayuki.qrcodegen.QrCode;

/** 在手机本地生成二维码，留白和整数像素保证扫码清晰。 */
public final class QrImage {
    public static Bitmap render(String content) {
        QrCode code=QrCode.encodeText(content,QrCode.Ecc.MEDIUM);
        int quiet=4, scale=8, side=(code.size+quiet*2)*scale;
        int[] pixels=new int[side*side];
        for(int y=0;y<side;y++) for(int x=0;x<side;x++) {
            int col=x/scale-quiet, row=y/scale-quiet;
            pixels[y*side+x]=code.getModule(col,row)?Color.BLACK:Color.WHITE;
        }
        Bitmap image=Bitmap.createBitmap(side,side,Bitmap.Config.ARGB_8888);
        image.setPixels(pixels,0,side,0,0,side,side); return image;
    }
}
