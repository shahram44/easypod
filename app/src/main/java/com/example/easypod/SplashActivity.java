
package com.example.easypod;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION = 3000; // ✅ v4: ۳ ثانیه

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // ✅ v4: نوار وضعیت (ساعت/آنتن) از همین لحظه مخفی شود
        hideStatusBar();

        // ✅ نمایش شمارهٔ نسخهٔ نصب‌شده در پایین صفحهٔ اسپلش
        //    (برای اطمینان این‌که واقعاً کدام نسخه روی گوشی اجرا می‌شود)
        String installedVersion = "";
        try {
            installedVersion = getPackageManager()
                    .getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            installedVersion = "";
        }
        TextView versionText = findViewById(R.id.splashVersion);
        if (versionText != null && !installedVersion.isEmpty()) {
            versionText.setText("EasyPod v" + installedVersion);
        }

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

    // ============================================================
    // ✅ v4: مخفی‌کردن نوار وضعیت (ساعت/آنتن) در اسپلش هم —
    //    دکمه‌های پایین گوشی (Back/Home) دست‌نخورده می‌مانند
    // ============================================================
    @SuppressWarnings("deprecation")
    private void hideStatusBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.view.WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(android.view.WindowInsets.Type.statusBars());
                }
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
    }
}
