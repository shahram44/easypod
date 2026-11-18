package com.example.easypod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/**
 * ✅ ابزار موقت عیب‌یابی (DIAG)
 * هر پیام با پیشوند «🔍 DIAG» روی صفحه گوشی (Toast) نمایش داده می‌شود
 * تا مشخص شود کدام مرحله از زنجیره ثبت توکن FCM می‌لنگد.
 * بعد از پیدا کردن مشکل، این نسخه با نسخه نهایی تمیز جایگزین می‌شود.
 */
public final class Diag {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Diag() {
    }

    public static void show(Context context, String message) {
        if (context == null) return;
        MAIN.post(() -> {
            try {
                Toast.makeText(context.getApplicationContext(),
                        "🔍 DIAG | " + message, Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {
            }
        });
    }
}
