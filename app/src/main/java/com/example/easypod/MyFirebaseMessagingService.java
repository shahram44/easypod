package com.example.easypod;

import android.util.Log;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

public class MyFirebaseMessagingService extends FirebaseMessagingService {
    private static final String TAG = "FCM_Service";

    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.d(TAG, "پیام FCM دریافت شد از: " + remoteMessage.getFrom());

        String title = "EasyPod";
        String message = "";
        String link = "";

        // اگر پیام دارای notification باشد
        if (remoteMessage.getNotification() != null) {
            title = remoteMessage.getNotification().getTitle();
            message = remoteMessage.getNotification().getBody();
        }

        // اگر پیام دارای data باشد (اطلاعات بیشتر)
        if (remoteMessage.getData().size() > 0) {
            if (remoteMessage.getData().containsKey("title")) {
                title = remoteMessage.getData().get("title");
            }
            if (remoteMessage.getData().containsKey("message")) {
                message = remoteMessage.getData().get("message");
            }
            if (remoteMessage.getData().containsKey("link")) {
                link = remoteMessage.getData().get("link");
            }
        }

        // نمایش نوتیفیکیشن
        int notificationId = (int) System.currentTimeMillis();
        NotificationHelper.showNotification(this, notificationId, title, message, link);
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "توکن جدید FCM: " + token);
        // ارسال توکن به سرور
        sendTokenToServer(token);
    }

    private void sendTokenToServer(String token) {
        // این متد را در گام بعدی پیاده‌سازی می‌کنیم
        TokenSender.send(getApplicationContext(), token);
    }
}
