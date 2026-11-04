package cn.tan.watchgate;

import android.service.notification.NotificationListenerService;

/** 仅在家长额外授权后用于控制媒体会话；不读取、记录或上传通知。 */
public final class MediaAccess extends NotificationListenerService { }
