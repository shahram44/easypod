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
import androidx.core.splashscreen.SplashScreen;
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
    private final static int CAMERA_PERMISSION_CODE = 101;

    private final static String APP_URL = "https://easypood.ir/cmms/";
    private final static String API_BASE = "https://easypood.ir/cmms/api";
    private final static String BASE_HOST = "https://easypood.ir/cmms";
    private final static long POLLING_INTERVAL = 30000;

    private final static long FCM_SYNC_MIN_INTERVAL = 2 * 60 * 1000;
    private final static long FCM_SYNC_DELAY = 5000;

    // ✅ متغیرهای SplashScreen
    private boolean webViewLoaded = false;
    private long splashStartTime = 0;
    private static final long SPLASH_MAX_DURATION = 2000; // حداکثر ۲ ثانیه

    private SharedPreferences prefs;
    private Handler pollingHandler;
    private Runnable pollingRunnable;
    private ExecutorService executor;
    private int lastNotificationId = 0;
    private boolean isPollingActive = false;
    private String pendingNotificationLink;

    // ✅ نگهداری درخواست دوربین که منتظر تأیید کاربر است
    private PermissionRequest pendingCameraPermissionRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // ============================================
        // ✅ نصب SplashScreen (باید قبل از super.onCreate باشد)
        // ============================================
        splashStartTime = System.currentTimeMillis();
        
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        
        // ✅ نگه داشتن splash تا لود WebView (حداکثر ۲ ثانیه)
        splashScreen.setKeepOnScreenCondition(() -> {
            // اگر WebView لود شده → splash ببند
            if (webViewLoaded) return false;
            
            // اگر بیش از ۲ ثانیه گذشته → splash ببند
            long elapsed = System.currentTimeMillis() - splashStartTime;
            if (elapsed > SPLASH_MAX_DURATION) {
                Log.w(TAG, "Splash timeout - WebView هنوز لود نشده، splash بسته می‌شود");
                return false;
            }
            
            // در غیر این صورت، splash باز بمان
            return true;
        });

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Log.d(TAG, "🚀 onCreate شروع شد");
        Log.d(TAG, "📱 SDK version: " + Build.VERSION.SDK_INT);
        Log.d(TAG, "📱 Device: " + Build.MANUFACTURER + " " + Build.MODEL);

        prefs = getSharedPreferences("easypod_prefs", MODE_PRIVATE);
        lastNotificationId = prefs.getInt("last_notification_id", 0);
        executor = Executors.newSingleThreadExecutor();

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        errorLayout = findViewById(R.id.errorLayout);
        retryButton = findViewById(R.id.retryButton);

        // بررسی وضعیت دسترسی دوربین در لحظه شروع
        checkCameraPermissionStatus();

        requestNotificationPermission();
        setupWebView();
        setupSwipeRefresh();
        setupRetryButton();

        handleNotificationIntent(getIntent());
        String initialDeepLink = extractDeepLinkUrl(getIntent());

        if (isNetworkAvailable()) {
            if (initialDeepLink != null) {
                Log.d(TAG, "🚀 باز کردن Deep Link اولیه: " + initialDeepLink);
                webView.loadUrl(initialDeepLink);
            } else {
                webView.loadUrl(APP_URL);
            }
        } else {
            showErrorPage();
            // ✅ در صورت عدم اتصال، splash را ببند
            webViewLoaded = true;
        }
    }

    /**
     * ✅ بررسی وضعیت دسترسی دوربین در لحظه شروع
     */
    private void checkCameraPermissionStatus() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "✅ دسترسی دوربین: قبلاً داده شده");
        } else {
            Log.d(TAG, "⚠️ دسترسی دوربین: هنوز داده نشده");
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.CAMERA},
                    CAMERA_PERMISSION_CODE
            );
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
        Log.d(TAG, "🔧 setupWebView شروع");

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setAllowFileAccessFromFileURLs(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "📄 onPageStarted: " + url);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                errorLayout.setVisibility(View.GONE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "✅ onPageFinished: " + url);
                
                // ✅ علامت زدن WebView به عنوان لود شده (برای بستن splash)
                webViewLoaded = true;
                
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                errorLayout.setVisibility(View.GONE);

                if (!isPollingActive) startPolling();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    checkIfLoggedInAndSync();
                }, FCM_SYNC_DELAY);

                openPendingNotificationLink();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.e(TAG, "❌ onReceivedError: " + error.getDescription());
                    
                    // ✅ در صورت خطا هم splash را ببند
                    webViewLoaded = true;
                    
                    showErrorPage();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) progressBar.setVisibility(View.GONE);
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                Log.d(TAG, "🔔 onPermissionRequest صدا زده شد!");
                Log.d(TAG, "   Origin: " + request.getOrigin());
                Log.d(TAG, "   Resources: " + java.util.Arrays.toString(request.getResources()));

                boolean needCamera = false;
                boolean needAudio = false;
                for (String resource : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                        needCamera = true;
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                        needAudio = true;
                    }
                }

                Log.d(TAG, "   نیاز به دوربین: " + needCamera + " | نیاز به میکروفن: " + needAudio);

                if (!needCamera) {
                    runOnUiThread(() -> {
                        try {
                            request.grant(request.getResources());
                        } catch (Exception e) {
                            Log.e(TAG, "خطا در grant: " + e.getMessage());
                        }
                    });
                    return;
                }

                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "⚠️ دسترسی دوربین نداریم، درخواست از کاربر");
                    pendingCameraPermissionRequest = request;
                    ActivityCompat.requestPermissions(
                            MainActivity.this,
                            new String[]{Manifest.permission.CAMERA},
                            CAMERA_PERMISSION_CODE
                    );
                    return;
                }

                Log.d(TAG, "✅ دسترسی دوربین داریم، grant می‌کنیم");
                runOnUiThread(() -> {
                    try {
                        request.grant(request.getResources());
                        Log.d(TAG, "✅ grant موفق");
                    } catch (Exception e) {
                        Log.e(TAG, "❌ خطا در grant: " + e.getMessage());
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

    private void checkIfLoggedInAndSync() {
        if (webView == null) return;

        webView.evaluateJavascript(
            "(function() {" +
            "   var navbarUser = document.querySelector('.navbar-user');" +
            "   var loginForm = document.querySelector('form#loginFormSubmit');" +
            "   var isLoggedIn = (navbarUser !== null) && (loginForm === null);" +
            "   var userIdMeta = document.querySelector('meta[name=\"user-id\"]');" +
            "   if (userIdMeta && userIdMeta.content && parseInt(userIdMeta.content) > 0) {" +
            "       isLoggedIn = true;" +
            "   }" +
            "   return isLoggedIn ? 'YES' : 'NO';" +
            "})()",
            result -> {
                boolean isLoggedIn = result != null && result.contains("YES");
                if (isLoggedIn) {
                    syncFcmTokenWithServer();
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

    private void syncFcmTokenWithServer() {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful() || task.getResult() == null) {
                Log.e(TAG, "syncFcm: خطا", task.getException());
                return;
            }

            String token = task.getResult();
            long lastSyncTime = prefs.getLong("last_fcm_sync_time", 0);
            long now = System.currentTimeMillis();

            if (now - lastSyncTime < FCM_SYNC_MIN_INTERVAL) {
                return;
            }

            TokenSender.send(getApplicationContext(), token);
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

    private String extractDeepLinkUrl(Intent intent) {
        if (intent == null) return null;
        Uri data = intent.getData();
        if (data == null) return null;

        String scheme = data.getScheme();
        if (!"easypod".equalsIgnoreCase(scheme)) {
            return null;
        }

        String host = data.getHost();
        String path = data.getPath();

        if (host == null) host = "";

        String fullPath;
        if (path == null || path.isEmpty()) {
            fullPath = "/" + host;
        } else {
            fullPath = "/" + host + path;
        }

        return BASE_HOST + fullPath;
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

        String deepLinkUrl = extractDeepLinkUrl(intent);
        if (deepLinkUrl != null && webView != null) {
            webView.loadUrl(deepLinkUrl);
            return;
        }

        openPendingNotificationLink();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        Log.d(TAG, "📋 onRequestPermissionsResult: requestCode=" + requestCode);

        if (requestCode == NOTIFICATION_PERMISSION_CODE) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "برای دریافت اعلان‌های EasyPod، اجازهٔ اعلان را فعال کنید.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (requestCode == CAMERA_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "✅ دسترسی دوربین داده شد");
                Toast.makeText(this, "✅ دسترسی دوربین فعال شد. اکنون می‌توانید QR اسکن کنید.", Toast.LENGTH_SHORT).show();

                if (pendingCameraPermissionRequest != null) {
                    final PermissionRequest req = pendingCameraPermissionRequest;
                    pendingCameraPermissionRequest = null;
                    runOnUiThread(() -> {
                        try {
                            req.grant(req.getResources());
                            Log.d(TAG, "✅ grant پس از تأیید کاربر");
                        } catch (Exception e) {
                            Log.e(TAG, "❌ خطا در grant: " + e.getMessage());
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
