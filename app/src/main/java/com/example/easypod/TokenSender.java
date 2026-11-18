package com.example.easypod;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.webkit.CookieManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import org.json.JSONObject;

public class TokenSender {
    private static final String TAG = "FCM_TokenSender";
    private static final String API_URL = "https://easypood.ir/cmms/api/device/register";
    private static final String COOKIE_URL = "https://easypood.ir/cmms/";
    private static final String PREFS_NAME = "easypod_prefs";

    public static void send(Context context, String token) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                // ✅ اطمینان از اینکه CookieManager آماده است
                CookieManager cookieManager = CookieManager.getInstance();
                cookieManager.setAcceptCookie(true);
                cookieManager.flush();

                // کمی صبر تا flush کامل شود
                Thread.sleep(500);

                String cookie = cookieManager.getCookie(COOKIE_URL);

                // ✅ اگر Cookie خالی است، اصلاً درخواست نده
                if (cookie == null || cookie.isEmpty()) {
                    Log.e(TAG, "❌ Cookie خالی است - کاربر لاگین نیست یا سشن منقضی شده");
                    Log.e(TAG, "❌ توکن ارسال نشد. لطفاً دوباره در اپ لاگین کنید.");
                    return;
                }

                Log.d(TAG, "✅ Cookie دریافت شد (طول: " + cookie.length() + " کاراکتر)");

                URL url = new URL(API_URL);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("X-Requested-With", "XMLHttpRequest");
                conn.setRequestProperty("Cookie", cookie);
                conn.setDoOutput(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);

                // ساخت payload
                JSONObject payload = new JSONObject();
                payload.put("token", token);
                payload.put("platform", "android");
                String jsonBody = payload.toString();

                Log.d(TAG, "📤 ارسال توکن به: " + API_URL);
                Log.d(TAG, "📤 طول توکن FCM: " + token.length() + " کاراکتر");

                // ارسال درخواست
                OutputStream os = conn.getOutputStream();
                os.write(jsonBody.getBytes("UTF-8"));
                os.flush();
                os.close();

                int responseCode = conn.getResponseCode();
                Log.d(TAG, "📥 پاسخ سرور: " + responseCode);

                // خواندن پاسخ
                BufferedReader reader;
                if (responseCode >= 200 && responseCode < 300) {
                    reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                } else {
                    reader = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                }

                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                Log.d(TAG, "📥 پاسخ: " + response.toString());

                SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

                if (responseCode >= 200 && responseCode < 300) {
                    // ✅ موفقیت: توکن را ذخیره کن
                    prefs.edit()
                            .putString("registered_fcm_token", token)
                            .putLong("last_fcm_sync_time", System.currentTimeMillis())
                            .apply();

                    Log.d(TAG, "✅ توکن با موفقیت در سرور ثبت شد");
                } else {
                    // ❌ خطا: توکن قبلی را پاک کن تا دفعه بعد دوباره تلاش کند
                    prefs.edit()
                            .remove("registered_fcm_token")
                            .remove("last_fcm_sync_time")
                            .apply();

                    Log.w(TAG, "❌ توکن در سرور ثبت نشد (خطای " + responseCode + ")");
                }

            } catch (Exception e) {
                Log.e(TAG, "❌ خطا در TokenSender: " + e.getMessage(), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
}
