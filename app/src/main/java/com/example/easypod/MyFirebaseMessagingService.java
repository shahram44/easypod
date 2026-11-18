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
            if (remoteMessage.getNotification().getTitle() != null) {
                title = remoteMessage.getNotification().getTitle();
            }
            if (remoteMessage.getNotification().getBody() != null) {
                message = remoteMessage.getNotification().getBody();
            }
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

        // محافظت در برابر مقادیر null (اعلان بدون عنوان یا متن)
        if (title == null || title.trim().isEmpty()) {
            title = "EasyPod";
        }
        if (message == null) {
            message = "";
        }

        // ✅ خواندن شناسه اعلان از سرور (اگر وجود داشته باشد)
        //    شناسه معتبر فقط همان است که سرور در data می‌فرستد؛
        //    fallback (زمان سیستم) هرگز نباید در prefs ذخیره شود چون
        //    خط آخرین شناسه (last_notification_id) را خراب می‌کند و
        //    polling دیگر هیچ اعلانی را دریافت نخواهد کرد.
        Integer serverId = null;
        try {
            String id = remoteMessage.getData().get("notification_id");
            if (id != null && !id.isEmpty()) {
                serverId = Integer.parseInt(id);
            }
        } catch (NumberFormatException ignored) {
            Log.w(TAG, "شناسه اعلان FCM معتبر نیست");
        }

        // ✅ جلوگیری از نمایش تکراری اعلان:
        //    وقتی اپ در حال اجراست، polling هر ۳۰ ثانیه اعلان‌های جدید را
        //    می‌گیرد و نمایش می‌دهد و شناسه‌اش را در prefs ثبت می‌کند.
        //    اگر FCM با همان شناسه بعدتر برسد، نباید دوباره نمایش داده شود.
        if (serverId != null) {
            android.content.SharedPreferences prefs =
                getSharedPreferences("easypod_prefs", MODE_PRIVATE);
            int lastShownId = prefs.getInt("last_notification_id", 0);
            if (serverId <= lastShownId) {
                Log.d(TAG, "اعلان شناسه " + serverId + " قبلاً نمایش داده شده است — تکراری، رد شد");
                return;
            }
        }

        // نمایش نوتیفیکیشن
        int notificationId = (serverId != null)
                ? serverId
                : (int) (System.currentTimeMillis() & 0x7fffffff);

        // فقط شناسه‌های واقعی سرور ثبت می‌شوند (نه fallback زمانی)
        if (serverId != null) {
            rememberLastNotificationId(notificationId);
        }

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
