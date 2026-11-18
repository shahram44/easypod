
package com.example.easypod;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION = 1500; // ۱.۵ ثانیه

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // ✅ بعد از ۱.۵ ثانیه به MainActivity می‌رود
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(SplashActivity.this, MainActivity.class);

            // ✅ اگر از Deep Link یا Notification آمده، به MainActivity پاس بده
            Intent originalIntent = getIntent();
            if (originalIntent != null && originalIntent.getData() != null) {
                intent.setData(originalIntent.getData());
            }

            // ✅ لینک اعلان را پاس بده.
            //    حالت ۱) اعلان اپ خودمان: extra با نام notification_link
            //    حالت ۲) اعلان سیستمی FCM (وقتی اپ بسته/پس‌زمینه است و کاربر
            //            روی اعلان می‌زند): داده‌های پیام به‌صورت extra با نام
            //            link (کلید data سرور) به Activity لانچر ارسال می‌شوند.
            if (originalIntent != null) {
                String notificationLink = null;
                if (originalIntent.hasExtra("notification_link")) {
                    notificationLink = originalIntent.getStringExtra("notification_link");
                } else if (originalIntent.hasExtra("link")) {
                    notificationLink = originalIntent.getStringExtra("link");
                }
                if (notificationLink != null && !notificationLink.trim().isEmpty()) {
                    intent.putExtra("notification_link", notificationLink.trim());
                }
            }

            startActivity(intent);
            finish();
        }, SPLASH_DURATION);
    }
}
