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
        int notificationId = getNotificationId(remoteMessage);
        rememberLastNotificationId(notificationId);
        NotificationHelper.showNotification(this, notificationId, title, message, link);
    }

    @Override
    public void onNewToken(String token) {
        Log.d(TAG, "توکن جدید FCM: " + token);
        // ارسال توکن به سرور
        sendTokenToServer(token);
    }

    private void sendTokenToServer(String token) {
        TokenSender.send(getApplicationContext(), token);
    }

    private int getNotificationId(RemoteMessage remoteMessage) {
        try {
            String id = remoteMessage.getData().get("notification_id");
            if (id != null && !id.isEmpty()) {
                return Integer.parseInt(id);
            }
        } catch (NumberFormatException ignored) {
            Log.w(TAG, "شناسه اعلان FCM معتبر نیست");
        }
        return (int) (System.currentTimeMillis() & 0x7fffffff);
    }

    private void rememberLastNotificationId(int notificationId) {
        if (notificationId <= 0) return;

        android.content.SharedPreferences prefs =
            getSharedPreferences("easypod_prefs", MODE_PRIVATE);
        int lastId = prefs.getInt("last_notification_id", 0);
        if (notificationId > lastId) {
            prefs.edit().putInt("last_notification_id", notificationId).apply();
        }
    }
}
