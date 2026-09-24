package com.example.easypod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

public class TokenSender {
    private static final String TAG = "FCM_TokenSender";
    private static final String API_URL = "https://easypood.ir/cmms/api/device/register";

    public static void send(Context context, String token) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                
                // ارسال کوکی برای احراز هویت
                String cookie = CookieManager.getInstance().getCookie("https://easypood.ir/cmms/");
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("platform", "android");
                String jsonBody = payload.toString();
                
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "پاسخ سرور: " + responseCode);

                // فقط پس از ثبت موفق توکن را ثبت‌شده بدان. در غیر این صورت
                // onPageFinished پس از ورود کاربر دوباره آن را ارسال خواهد کرد.
                if (responseCode >= 200 && responseCode < 300) {
                    SharedPreferences prefs = context.getSharedPreferences("easypod_prefs", Context.MODE_PRIVATE);
                    prefs.edit().putString("registered_fcm_token", token).apply();
                } else {
                    Log.w(TAG, "توکن در سرور ثبت نشد؛ در صفحه بعد دوباره تلاش می‌شود.");
                }
                
            } catch (Exception e) {
                Log.e(TAG, "خطا در ارسال توکن: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
