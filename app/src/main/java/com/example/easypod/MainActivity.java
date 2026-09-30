package com.example.easypod;

import android.Manifest;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.firebase.messaging.FirebaseMessaging;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "EasyPod_Main";

    private WebView webView;
    private ProgressBar progressBar;
    private SwipeRefreshLayout swipeRefresh;
    private LinearLayout errorLayout;
    private Button retryButton;
    private ValueCallback<Uri[]> filePathCallback;

    private final static int FILE_CHOOSER_RESULT_CODE = 1;
    private final static int NOTIFICATION_PERMISSION_CODE = 100;
    private final static String APP_URL = "https://easypood.ir/cmms/";
    private final static String API_BASE = "https://easypood.ir/cmms/api";
    private final static long POLLING_INTERVAL = 30000;

    // ✅ حداقل فاصله بین دو sync موفق FCM (5 دقیقه)
    private final static long FCM_SYNC_MIN_INTERVAL = 5 * 60 * 1000;
    // ✅ تأخیر پس از لود صفحه قبل از ارسال توکن (3 ثانیه)
    private final static long FCM_SYNC_DELAY = 3000;

    private SharedPreferences prefs;
    private Handler pollingHandler;
    private Runnable pollingRunnable;
    private ExecutorService executor;
    private int lastNotificationId = 0;
    private boolean isPollingActive = false;
    private String pendingNotificationLink;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("easypod_prefs", MODE_PRIVATE);
        lastNotificationId = prefs.getInt("last_notification_id", 0);
        executor = Executors.newSingleThreadExecutor();

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        errorLayout = findViewById(R.id.errorLayout);
        retryButton = findViewById(R.id.retryButton);

        requestNotificationPermission();
        setupWebView();
        setupSwipeRefresh();
        setupRetryButton();

        handleNotificationIntent(getIntent());

        if (isNetworkAvailable()) {
            webView.loadUrl(APP_URL);
        } else {
            showErrorPage();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_CODE);
            }
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                errorLayout.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                errorLayout.setVisibility(View.GONE);

                if (!isPollingActive) startPolling();

                // ✅ با تأخیر، توکن FCM را با سرور سینک کن
                // تأخیر برای اطمینان از ثبت کامل Cookie در WebView
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    syncFcmTokenWithServer();
                }, FCM_SYNC_DELAY);

                openPendingNotificationLink();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showErrorPage();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                                             ValueCallback<Uri[]> filePathCallback,
                                             FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;
                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                                        String contentDisposition, String mimetype, long contentLength) {
                try {
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setMimeType(mimetype);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    request.addRequestHeader("cookie", cookies);
                    request.addRequestHeader("User-Agent", userAgent);
                    request.setDescription("در حال دانلود فایل...");
                    request.setTitle(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
                    request.allowScanningByMediaScanner();
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
                    DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "دانلود شروع شد...", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Toast.makeText(getApplicationContext(), "خطا در دانلود", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void startPolling() {
        if (isPollingActive) return;
        isPollingActive = true;
        pollingHandler = new Handler(Looper.getMainLooper());
        pollingRunnable = new Runnable() {
            @Override
            public void run() {
                fetchNewNotifications();
                if (pollingHandler != null) {
                    pollingHandler.postDelayed(this, POLLING_INTERVAL);
                }
            }
        };
        pollingHandler.postDelayed(pollingRunnable, 3000);
    }

    private void stopPolling() {
        isPollingActive = false;
        if (pollingHandler != null && pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
        }
    }

    private void fetchNewNotifications() {
        if (!isNetworkAvailable()) return;
        // شاید یک Push همین حالا در FirebaseMessagingService دریافت شده باشد؛
        // مقدار ذخیره‌شده را بخوان تا polling همان اعلان را دوباره نشان ندهد.
        lastNotificationId = Math.max(lastNotificationId, prefs.getInt("last_notification_id", 0));
        executor.execute(() -> {
            try {
                URL url = new URL(API_BASE + "/notifications/since/" + lastNotificationId);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Cookie", CookieManager.getInstance().getCookie(API_BASE));
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return;

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) response.append(line);
                reader.close();
                conn.disconnect();

                JSONObject json = new JSONObject(response.toString());
                if (!"success".equals(json.optString("status"))) return;

                JSONArray data = json.optJSONArray("data");
                if (data == null || data.length() == 0) return;

                for (int i = 0; i < data.length(); i++) {
                    JSONObject notif = data.getJSONObject(i);
                    int id = notif.getInt("id");
                    String title = notif.getString("title");
                    String message = notif.getString("message");
                    String link = notif.optString("link", "");
                    String icon = notif.optString("icon", "🔔");

                    final int finalId = id;
                    runOnUiThread(() -> NotificationHelper.showNotification(
                            MainActivity.this, finalId, icon + " " + title, message, link));

                    if (id > lastNotificationId) lastNotificationId = id;
                }

                prefs.edit().putInt("last_notification_id", lastNotificationId).apply();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * ✅ نسخه اصلاح‌شده sync توکن FCM با سرور
     *
     * مشکلات نسخه قبلی که رفع شده:
     *   1. شرط !token.equals(registeredToken) باعث می‌شد توکن جدید فقط اگر تغییر کرده بود ارسال شود.
     *      اما اگر سرور توکن را گم کرده باشد، هرگز دوباره دریافت نمی‌کرد.
     *   2. onPageFinished بلافاصله اجرا می‌شد و Cookie ممکن بود آماده نباشد.
     *   3. درخواست‌های مکرر به سرور در هر بار تغییر صفحه.
     *
     * راه‌حل جدید:
     *   - همیشه توکن ارسال می‌شود (نه فقط اگر تغییر کرده باشد)
     *   - با فاصله حداقل 5 دقیقه بین دو ارسال
     *   - پس از 3 ثانیه تأخیر از onPageFinished برای اطمینان از ثبت Cookie
     *   - فقط در صفحات غیر از لاگین اجرا می‌شود
     */
    private void syncFcmTokenWithServer() {
        // چک: در صفحه لاگین یا ثبت‌نام نیستیم
        String currentUrl = webView.getUrl();
        if (currentUrl == null) {
            Log.d(TAG, "syncFcm: URL null، صرف‌نظر");
            return;
        }

        boolean isLoginPage = currentUrl.contains("/login")
                || currentUrl.contains("/register")
                || currentUrl.endsWith("/cmms/")
                || currentUrl.endsWith("/cmms");

        if (isLoginPage) {
            Log.d(TAG, "syncFcm: در صفحه ورود/ثبت‌نام هستیم، sync نمی‌کنیم");
            return;
        }

        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.e(TAG, "syncFcm: خطا در دریافت توکن از Firebase", task.getException());
                return;
            }

            String token = task.getResult();
            Log.d(TAG, "syncFcm: توکن Firebase دریافت شد (طول: " + token.length() + ")");

            // ✅ چک فاصله زمانی: در 5 دقیقه اخیر sync نکرده باشیم
            long lastSyncTime = prefs.getLong("last_fcm_sync_time", 0);
            long now = System.currentTimeMillis();

            if (now - lastSyncTime < FCM_SYNC_MIN_INTERVAL) {
                Log.d(TAG, "syncFcm: کمتر از 5 دقیقه از sync قبلی، صرف‌نظر");
                return;
            }

            Log.d(TAG, "syncFcm: ارسال توکن به سرور...");
            TokenSender.send(getApplicationContext(), token);

            // ثبت زمان آخرین sync
            prefs.edit().putLong("last_fcm_sync_time", now).apply();
        });
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;

        String link = intent.getStringExtra("notification_link");
        if (link != null && !link.trim().isEmpty()) {
            pendingNotificationLink = link.trim();
        }
    }

    private void openPendingNotificationLink() {
        if (pendingNotificationLink == null || webView == null) return;

        String link = pendingNotificationLink;
        String targetUrl;
        if (link.startsWith("/")) {
            targetUrl = "https://easypood.ir" + link;
        } else if (link.startsWith(APP_URL)) {
            targetUrl = link;
        } else {
            // پیوندهای بیرونی از Push اجرا نمی‌شوند.
            pendingNotificationLink = null;
            return;
        }

        pendingNotificationLink = null;
        if (!targetUrl.equals(webView.getUrl())) {
            webView.loadUrl(targetUrl);
        }
    }

    private void setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener(() -> {
            if (isNetworkAvailable()) webView.reload();
            else {
                swipeRefresh.setRefreshing(false);
                showErrorPage();
            }
        });
        swipeRefresh.setColorSchemeResources(android.R.color.holo_blue_bright);
    }

    private void setupRetryButton() {
        retryButton.setOnClickListener(v -> {
            if (isNetworkAvailable()) {
                errorLayout.setVisibility(View.GONE);
                webView.loadUrl(APP_URL);
            } else {
                Toast.makeText(MainActivity.this, "هنوز اینترنت وصل نیست", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showErrorPage() {
        errorLayout.setVisibility(View.VISIBLE);
        progressBar.setVisibility(View.GONE);
        swipeRefresh.setRefreshing(false);
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
            return activeNetwork != null && activeNetwork.isConnected();
        }
        return false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPollingActive && pollingHandler != null) {
            pollingHandler.postDelayed(pollingRunnable, POLLING_INTERVAL);
        } else if (isNetworkAvailable()) {
            startPolling();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (pollingHandler != null && pollingRunnable != null) {
            pollingHandler.removeCallbacks(pollingRunnable);
        }
    }

    @Override
    protected void onDestroy() {
        stopPolling();
        if (executor != null) executor.shutdown();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotificationIntent(intent);
        openPendingNotificationLink();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_CODE
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "برای دریافت اعلان‌های EasyPod، اجازهٔ اعلان را فعال کنید.", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (filePathCallback == null) return;
            Uri[] results = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
