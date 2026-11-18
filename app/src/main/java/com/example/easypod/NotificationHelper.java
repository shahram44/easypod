package com.example.easypod;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.graphics.Color;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

public class NotificationHelper {

    // شناسه جدید باعث می‌شود کانال قدیمیِ احتمالاً بی‌صدا، تنظیمات نسخه جدید را به ارث نبرد.
    private static final String CHANNEL_ID = "easypod_notifications_v2";
    private static final String CHANNEL_NAME = "اعلانات EasyPod";

    public static void showNotification(Context context, int id, String title, String message, String link) {

        // ✅ Android 13+ : اگر کاربر اجازه اعلان نداده باشد، فراخوانی notify بی‌اثر است؛
        //    قبل از ساخت اعلان بررسی می‌کنیم تا از خطای lint و کار بیهوده جلوگیری شود.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        // ✅ اگر کاربر اعلان‌های اپ را در تنظیمات غیرفعال کرده باشد، اعلان نفرست
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return;
        }

        NotificationManager notificationManager =
            (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("اعلانات سیستم CMMS");
            channel.enableLights(true);
            channel.setLightColor(Color.BLUE);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 500, 200, 500});
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
            channel.setSound(sound, attributes);
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (link != null && !link.isEmpty()) {
            intent.putExtra("notification_link", link);
        }

        PendingIntent pendingIntent = PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_SOUND | NotificationCompat.DEFAULT_VIBRATE)
            .setVibrate(new long[]{0, 500, 200, 500});

        // ✅ با NotificationManagerCompat اعلان نمایش داده می‌شود (الگوی توصیه‌شده AndroidX)
        NotificationManagerCompat.from(context).notify(id, builder.build());
    }
}
