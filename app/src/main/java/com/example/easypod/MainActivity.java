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
import android.webkit.PermissionRequest;
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
    // ✅ جدید: کد درخواست دسترسی دوربین
    private final static int CAMERA_PERMISSION_CODE = 101;

    private final static String APP_URL = "https://easypood.ir/cmms/";
    private final static String API_BASE = "https://easypood.ir/cmms/api";
    private final static String BASE_HOST = "https://easypood.ir/cmms";
    private final static long POLLING_INTERVAL = 30000;
    // برای refresh باید یک کشش عمدی انجام شود، نه حرکت کوتاه هنگام اسکرول.
    private final static int REFRESH_TRIGGER_DISTANCE_DP = 144;

    // ✅ حداقل فاصله بین دو sync موفق FCM (2 دقیقه)
    private final static long FCM_SYNC_MIN_INTERVAL = 2 * 60 * 1000;
    // ✅ تأخیر پس از لود صفحه قبل از ارسال توکن (5 ثانیه)
    private final static long FCM_SYNC_DELAY = 5000;

    private SharedPreferences prefs;
    private Handler pollingHandler;
    private Runnable pollingRunnable;
    private ExecutorService executor;
    private int lastNotificationId = 0;
    private boolean isPollingActive = false;
    private String pendingNotificationLink;
<<<<<<< HEAD

    // ✅ جدید: نگهداری درخواست دسترسی دوربین که منتظر تأیید کاربر است
    private PermissionRequest pendingCameraPermissionRequest;
=======
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)

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

<<<<<<< HEAD
        // ✅ پردازش Intent اولیه (می‌تواند Deep Link یا نوتیفیکیشن باشد)
        handleNotificationIntent(getIntent());
        String initialDeepLink = extractDeepLinkUrl(getIntent());
=======
        handleNotificationIntent(getIntent());
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)

        if (isNetworkAvailable()) {
            if (initialDeepLink != null) {
                Log.d(TAG, "🚀 باز کردن Deep Link اولیه: " + initialDeepLink);
                webView.loadUrl(initialDeepLink);
            } else {
                webView.loadUrl(APP_URL);
            }
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

        // ✅ جدید: پشتیبانی از MediaPlayback و getUserMedia
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                configurePullToRefresh(url);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                errorLayout.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                configurePullToRefresh(url);
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                errorLayout.setVisibility(View.GONE);

                if (!isPollingActive) startPolling();
<<<<<<< HEAD

                // ✅ با تأخیر، توکن FCM را با سرور سینک کن
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    checkIfLoggedInAndSync();
                }, FCM_SYNC_DELAY);

=======
                syncFcmTokenWithServer();
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
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

            // ============================================================
            // ✅ جدید: مدیریت درخواست دسترسی (دوربین برای اسکن QR)
            // ============================================================
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                Log.d(TAG, "onPermissionRequest: " + java.util.Arrays.toString(request.getResources()));

                // بررسی اینکه آیا درخواست شامل دوربین است
                boolean needCamera = false;
                boolean needAudio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                        needCamera = true;
                    } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        needAudio = true;
                    }
                }

                // اگر فقط دوربین لازم است (اسکن QR)
                if (needCamera && !needAudio) {
                    // بررسی دسترسی دوربین در سطح اندروید
                    if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                            != PackageManager.PERMISSION_GRANTED) {
                        // هنوز دسترسی نداریم → از کاربر بخواهیم
                        pendingCameraPermissionRequest = request;
                        ActivityCompat.requestPermissions(
                                MainActivity.this,
                                new String[]{Manifest.permission.CAMERA},
                                CAMERA_PERMISSION_CODE
                        );
                        return;
                    }
                    // دسترسی داریم → به WebView بده
                    runOnUiThread(() -> {
                        try {
                            request.grant(request.getResources());
                        } catch (Exception e) {
                            Log.e(TAG, "خطا در grant کردن دسترسی دوربین: " + e.getMessage());
                            try { request.deny(); } catch (Exception ignored) {}
                        }
                    });
                    return;
                }

                // درخواست‌های دیگر (مثلاً میکروفن) → اجازه می‌دهیم
                // اگر نمی‌خواهید اجازه دهید، این قسمت را حذف کنید
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                    } catch (Exception e) {
                        Log.e(TAG, "خطا در grant کردن دسترسی: " + e.getMessage());
                        try { request.deny(); } catch (Exception ignored) {}
                    }
                });
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

    /**
     * ✅ بررسی با JavaScript که کاربر لاگین است یا نه
     */
    private void checkIfLoggedInAndSync() {
        if (webView == null) return;

        webView.evaluateJavascript(
            "(function() {" +
            "   var navbarUser = document.querySelector('.navbar-user');" +
            "   var loginForm = document.querySelector('form#loginFormSubmit');" +
            "   " +
            "   var isLoggedIn = (navbarUser !== null) && (loginForm === null);" +
            "   " +
            "   var userIdMeta = document.querySelector('meta[name=\"user-id\"]');" +
            "   if (userIdMeta && userIdMeta.content && parseInt(userIdMeta.content) > 0) {" +
            "       isLoggedIn = true;" +
            "   }" +
            "   " +
            "   return isLoggedIn ? 'YES' : 'NO';" +
            "})()",
            result -> {
                boolean isLoggedIn = result != null && result.contains("YES");
                Log.d(TAG, "بررسی لاگین: نتیجه = " + result + " | isLoggedIn = " + isLoggedIn);

                if (isLoggedIn) {
                    Log.d(TAG, "✅ کاربر لاگین است، شروع sync توکن FCM");
                    syncFcmTokenWithServer();
                } else {
                    Log.d(TAG, "❌ کاربر لاگین نیست، sync انجام نمی‌شود");
                }
            }
        );
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
<<<<<<< HEAD
=======
        // شاید یک Push همین حالا در FirebaseMessagingService دریافت شده باشد؛
        // مقدار ذخیره‌شده را بخوان تا polling همان اعلان را دوباره نشان ندهد.
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
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

<<<<<<< HEAD
    private void syncFcmTokenWithServer() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.e(TAG, "syncFcm: خطا در دریافت توکن از Firebase", task.getException());
                return;
            }

            String token = task.getResult();
            Log.d(TAG, "syncFcm: توکن Firebase دریافت شد (طول: " + token.length() + ")");

            long lastSyncTime = prefs.getLong("last_fcm_sync_time", 0);
            long now = System.currentTimeMillis();

            if (now - lastSyncTime < FCM_SYNC_MIN_INTERVAL) {
                Log.d(TAG, "syncFcm: کمتر از " + (FCM_SYNC_MIN_INTERVAL / 1000) + " ثانیه از sync قبلی، صرف‌نظر");
                return;
            }

            Log.d(TAG, "syncFcm: ارسال توکن به سرور...");
            TokenSender.send(getApplicationContext(), token);

            prefs.edit().putLong("last_fcm_sync_time", now).apply();
=======
    /**
     * FCM token پیش از ورود کاربر معتبر است، اما ثبت آن در سرور به سشنِ WebView نیاز دارد.
     * به همین دلیل پس از پایان هر صفحه نیز همگام‌سازی می‌کنیم تا بلافاصله پس از login ثبت شود.
     */
    private void syncFcmTokenWithServer() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) return;

            String token = task.getResult();
            String registeredToken = prefs.getString("registered_fcm_token", "");
            if (!token.equals(registeredToken)) {
                TokenSender.send(getApplicationContext(), token);
            }
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
        });
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent == null) return;
<<<<<<< HEAD
=======

>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
        String link = intent.getStringExtra("notification_link");
        if (link != null && !link.trim().isEmpty()) {
            pendingNotificationLink = link.trim();
        }
    }

<<<<<<< HEAD
    // ============================================================
    // ✅ جدید: استخراج URL از Deep Link
    // easypod://m/123  →  https://easypood.ir/cmms/m/123
    // ============================================================
    private String extractDeepLinkUrl(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;

        String scheme = data.getScheme();
        if (!"easypod".equalsIgnoreCase(scheme)) {
            return null;
        }

        // easypod://m/123 → path = "/123" و host = "m"
        // easypod://machines/view/123 → path = "/view/123" و host = "machines"
        String host = data.getHost();  // "m" یا "machines"
        String path = data.getPath();  // "/123" یا "/view/123"

        if (host == null) host = "";

        String fullPath;
        if (path == null || path.isEmpty()) {
            fullPath = "/" + host;
        } else {
            fullPath = "/" + host + path;
        }

        // ساخت URL کامل
        String finalUrl = BASE_HOST + fullPath;

        Log.d(TAG, "🔗 Deep Link استخراج شد: " + finalUrl);
        return finalUrl;
    }

    private void openPendingNotificationLink() {
        if (pendingNotificationLink == null || webView == null) return;
=======
    private void openPendingNotificationLink() {
        if (pendingNotificationLink == null || webView == null) return;

>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
        String link = pendingNotificationLink;
        String targetUrl;
        if (link.startsWith("/")) {
            targetUrl = "https://easypood.ir" + link;
        } else if (link.startsWith(APP_URL)) {
            targetUrl = link;
        } else {
<<<<<<< HEAD
            pendingNotificationLink = null;
            return;
        }
=======
            // پیوندهای بیرونی از Push اجرا نمی‌شوند.
            pendingNotificationLink = null;
            return;
        }

>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
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
        swipeRefresh.setDistanceToTriggerSync(dpToPx(REFRESH_TRIGGER_DISTANCE_DP));
    }

    /**
     * چت پیام‌ها را در یک div قابل‌اسکرول داخل WebView نمایش می‌دهد. SwipeRefreshLayout
     * از موقعیت آن div خبر ندارد و حرکت برای پیام‌های قدیمی را با refresh اشتباه می‌گیرد.
     * بنابراین pull-to-refresh در صفحه‌های چت غیرفعال است؛ در باقی صفحات فعال می‌ماند.
     */
    private void configurePullToRefresh(String url) {
        if (url == null || swipeRefresh == null) return;

        String path = Uri.parse(url).getPath();
        boolean isChatPage = path != null
            && (path.equals("/cmms/chat") || path.startsWith("/cmms/chat/"));

        if (isChatPage && swipeRefresh.isRefreshing()) {
            swipeRefresh.setRefreshing(false);
        }
        swipeRefresh.setEnabled(!isChatPage);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
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
<<<<<<< HEAD

        // ✅ بررسی Deep Link جدید (اگر اپ از قبل باز بود)
        String deepLinkUrl = extractDeepLinkUrl(intent);
        if (deepLinkUrl != null && webView != null) {
            Log.d(TAG, "🚀 Deep Link جدید: " + deepLinkUrl);
            webView.loadUrl(deepLinkUrl);
            return;
        }

=======
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
        openPendingNotificationLink();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
<<<<<<< HEAD

        // ===== اعلان‌ها =====
        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "برای دریافت اعلان‌های EasyPod، اجازهٔ اعلان را فعال کنید.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        // ===== ✅ دوربین (برای اسکن QR) =====
        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✅ دسترسی دوربین داده شد");
                if (pendingCameraPermissionRequest != null) {
                    final PermissionRequest req = pendingCameraPermissionRequest;
                    pendingCameraPermissionRequest = null;
                    runOnUiThread(() -> {
                        try {
                            req.grant(req.getResources());
                        } catch (Exception e) {
                            Log.e(TAG, "خطا در grant کردن دسترسی دوربین: " + e.getMessage());
                            try { req.deny(); } catch (Exception ignored) {}
                        }
                    });
                }
            } else {
                Log.w(TAG, "❌ دسترسی دوربین رد شد");
                Toast.makeText(this, "برای اسکن QR، دسترسی دوربین لازم است. لطفاً از تنظیمات اپ آن را فعال کنید.", Toast.LENGTH_LONG).show();
                if (pendingCameraPermissionRequest != null) {
                    final PermissionRequest req = pendingCameraPermissionRequest;
                    pendingCameraPermissionRequest = null;
                    runOnUiThread(() -> {
                        try { req.deny(); } catch (Exception ignored) {}
                    });
                }
            }
=======
        if (requestCode == NOTIFICATION_PERMISSION_CODE
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "برای دریافت اعلان‌های EasyPod، اجازهٔ اعلان را فعال کنید.", Toast.LENGTH_LONG).show();
>>>>>>> 5c32421 (رفع خطای Firebase با DataStore)
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
