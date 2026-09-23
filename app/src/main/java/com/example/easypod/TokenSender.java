package com.example.easypod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class TokenSender {
    private static final String TAG = "FCM_TokenSender";
    private static final String API_URL = "https://easypood.ir/cmms/api/device/register";

    public static void send(Context context, String token) {
        new Thread(() -> {
            try {
                URL url = new URL(API_URL);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                
                // ارسال کوکی برای احراز هویت
                String cookie = CookieManager.getInstance().getCookie("https://easypood.ir");
                if (cookie != null) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                String jsonBody = "{\"token\":\"" + token + "\",\"platform\":\"android\"}";
                
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "پاسخ سرور: " + responseCode);
                conn.disconnect();
                
                // ذخیره توکن به صورت محلی
                SharedPreferences prefs = context.getSharedPreferences("easypod_prefs", Context.MODE_PRIVATE);
                prefs.edit().putString("fcm_token", token).apply();
                
            } catch (Exception e) {
                Log.e(TAG, "خطا در ارسال توکن: " + e.getMessage());
            }
        }).start();
    }
}
