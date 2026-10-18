
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
            if (originalIntent != null && originalIntent.hasExtra("notification_link")) {
                intent.putExtra("notification_link", 
                    originalIntent.getStringExtra("notification_link"));
            }
            
            startActivity(intent);
            finish();
        }, SPLASH_DURATION);
    }
}
