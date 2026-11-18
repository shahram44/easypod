package com.example.easypod;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * ✅ ابزار موقت عیب‌یابی (DIAG)
 * هر پیام در سه جا ثبت می‌شود:
 *   ۱) پنل سیاه بالای صفحه اپ (diagLog) — می‌ماند و می‌شود عکس گرفت
 *   ۲) Toast روی صفحه (روی گوشی‌هایی که Toast را می‌بندند نمایش داده نمی‌شود)
 *   ۳) Logcat با تگ EasyPod_DIAG
 * بعد از پیدا کردن مشکل، این نسخه با نسخه نهایی تمیز جایگزین می‌شود.
 */
public final class Diag {
    private static final String TAG = "EasyPod_DIAG";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int MAX_LINES = 16;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static TextView panel;
    private static final SimpleDateFormat TIME =
            new SimpleDateFormat("HH:mm:ss", Locale.US);

    private Diag() {
    }

    /** MainActivity پنل را در لحظه شروع به Diag وصل می‌کند */
    public static void attach(TextView view) {
        panel = view;
        if (panel != null) {
            MAIN.post(() -> render("DIAG READY — 1.2.2-diag2"));
        }
    }

    public static void show(Context context, String message) {
        Log.d(TAG, message);
        MAIN.post(() -> render(message));
        if (context != null) {
            MAIN.post(() -> {
                try {
                    Toast.makeText(context.getApplicationContext(),
                            "🔍 DIAG | " + message, Toast.LENGTH_LONG).show();
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static void render(String message) {
        LINES.addLast(TIME.format(new Date()) + " " + message);
        while (LINES.size() > MAX_LINES) {
            LINES.removeFirst();
        }
        if (panel != null) {
            StringBuilder sb = new StringBuilder();
            for (String l : LINES) {
                sb.append(l).append('\n');
            }
            panel.setText(sb.toString());
        }
    }
}
