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
import android.widget.TextView;
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
    private final static int CAMERA_PERMISSION_CODE = 101;

    private final static String APP_URL = "https://easypood.ir/cmms/";
    private final static String API_BASE = "https://easypood.ir/cmms/api";
    private final static String BASE_HOST = "https://easypood.ir/cmms";
    private final static long POLLING_INTERVAL = 30000;

    private final static long FCM_SYNC_MIN_INTERVAL = 2 * 60 * 1000;
    private final static long FCM_SYNC_DELAY = 5000;

    // ✅ v4: اسپلش — حداقل ۳ ثانیه نمایش + ماندن تا پایان بارگذاری اولیه
    private static final long SPLASH_MIN_DISPLAY_MS = 3000;
    // ✅ v4: اگر بارگذاری صفحه پس از ۲۰ ثانیه کامل نشد → پیام تلاش مجدد
    private static final long LOAD_TIMEOUT_MS = 20000;
    // ✅ v4: فاصله کشیدن لازم برای رفرش (dp) — پیش‌فرض ۶۴dp خیلی حساس بود
    private static final long PULL_REFRESH_TRIGGER_DP = 200;

    private SharedPreferences prefs;
    private Handler pollingHandler;
    private Runnable pollingRunnable;
    private ExecutorService executor;
    private int lastNotificationId = 0;
    private boolean isPollingActive = false;
    private String pendingNotificationLink;

    // ✅ نگهداری درخواست دوربین که منتظر تأیید کاربر است
    private PermissionRequest pendingCameraPermissionRequest;

    // ✅ v4: لایه اسپلش و مدیریت بارگذاری اولیه
    private View splashOverlay;
    private View splashTimeoutBox;
    private Button splashRetryButton;
    private Handler splashHandler;
    private long splashShownAt;
    private boolean initialPageLoaded = false;
    private boolean splashHidden = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // ✅ v4: مخفی‌کردن نوار وضعیت (ساعت/آنتن) — دکمه‌های پایین باقی می‌مانند
        hideStatusBar();

        Log.d(TAG, "🚀 MainActivity onCreate شروع شد");
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

        // ✅ v4: لایه اسپلش — همان تصویر صفحهٔ اول تا پایان بارگذاری اولیه
        //    نمایش داده می‌شود (اینترنت ضعیف → اسپلش نمی‌پرد)
        splashOverlay = findViewById(R.id.splashOverlay);
        splashTimeoutBox = findViewById(R.id.splashTimeoutBox);
        splashRetryButton = findViewById(R.id.splashRetryButton);
        splashHandler = new Handler(Looper.getMainLooper());
        splashShownAt = System.currentTimeMillis();
        setSplashOverlayVersion();
        if (splashRetryButton != null) {
            splashRetryButton.setOnClickListener(v -> retryInitialLoad());
        }
        startInitialLoadWatchdog();

        // بررسی وضعیت دسترسی دوربین در لحظه شروع حذف شد —
        // ✅ مجوز دوربین حالا فقط وقتی کاربر واقعاً اسکن QR را باز می‌کند
        //    از طریق onPermissionRequest (WebChromeClient) درخواست می‌شود.

        requestNotificationPermission();
        setupWebView();
        setupSwipeRefresh();
        setupRetryButton();

        // ✅ پردازش Intent که از SplashActivity آمده
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
        // ✅ دسترسی فایل و content خاموش می‌شود — اپ فقط https://easypood.ir را
        //    باز می‌کند و به URLهای file:// و content:// نیازی ندارد؛
        //    این کار سطح حمله XSS محتوا به فایل‌های محلی را از بین می‌برد.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setSupportZoom(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // ✅ این دو تنظیم فقط برای صفحات file:// معنی دارند؛ چون هیچ صفحه‌ای از
        //    file:// باز نمی‌شود، به‌صورت ایمن خاموش می‌شوند (جلوگیری از خواندن
        //    فایل‌های محلی توسط اسکریپت‌های ناخواسته).
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setAllowFileAccessFromFileURLs(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // ✅ حالت سازگار با مرورگر (کروم): محتوای http با سیاست cleartext
            //    (خاموش در Manifest) مهار می‌شود؛ فقط تصاویر ناچ http مثل کروم
            //    اجازه شناور شدن دارند — امن‌تر از ALWAYS_ALLOW.
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        }

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            // ✅ مدیریت لینک‌ها: لینک‌های داخلی easypod.ir داخل WebView باز
            //    می‌شوند؛ شماره تلفن، پیامک، ایمیل، نقشه و سایر اپ‌ها خارج از اپ؛
            //    سایر سایت‌ها هم در مرورگر خارجی باز می‌شوند نه داخل اپ.
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleUrlNavigation(request.getUrl());
            }

            // برای سازگاری با WebViewهای قدیمی‌تر که نسخه String را صدا می‌زنند
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrlNavigation(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                Log.d(TAG, "📄 onPageStarted: " + url);
                progressBar.setVisibility(View.VISIBLE);
                progressBar.setProgress(0);
                errorLayout.setVisibility(View.GONE);

                // ✅ v4: در صفحه چت‌ها Pull-to-Refresh غیرفعال می‌شود تا
                //    اسکرول کردن و خواندن پیام‌های قبلی ممکن باشد
                swipeRefresh.setEnabled(!isChatUrl(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                Log.d(TAG, "✅ onPageFinished: " + url);
                progressBar.setVisibility(View.GONE);
                swipeRefresh.setRefreshing(false);
                errorLayout.setVisibility(View.GONE);

                // ✅ v4: پایان بارگذاری اولیه — تایمر ۲۰ ثانیه لغو و اسپلش
                //    (پس از حداقل ۳ ثانیه) بسته می‌شود
                if (!initialPageLoaded) {
                    initialPageLoaded = true;
                    cancelInitialLoadWatchdog();
                }
                dismissSplashOverlayWhenReady();

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
        if (link == null || link.trim().isEmpty()) {
            // ✅ اعلان سیستمی FCM (tap در حالت پس‌زمینه) کلید data سرور را
            //    به‌صورت extra با نام link می‌فرستد — به‌عنوان پشتیبان خوانده می‌شود
            link = intent.getStringExtra("link");
        }
        if (link != null && !link.trim().isEmpty()) {
            pendingNotificationLink = link.trim();
        }
    }

    /**
     * ✅ مسیریابی هوشمند لینک‌ها
     * @return true اگر لینک خارج از WebView مدیریت شده باشد
     */
    private boolean handleUrlNavigation(Uri uri) {
        if (uri == null || uri.getScheme() == null) return false;

        String scheme = uri.getScheme().toLowerCase();

        // ۱) http/https: فقط easypood.ir داخل اپ می‌ماند
        if (scheme.equals("https") || scheme.equals("http")) {
            String host = uri.getHost();
            if (host != null && host.equalsIgnoreCase("easypood.ir")) {
                return false; // داخل WebView باز شود
            }
            openExternal(uri); // سایر سایت‌ها → مرورگر خارجی
            return true;
        }

        // ۲) شماره تلفن / پیامک / ایمیل / نقشه / سایر اپ‌ها → خارج از اپ
        if (scheme.equals("tel") || scheme.equals("sms") || scheme.equals("smsto")
                || scheme.equals("mailto") || scheme.equals("geo")
                || scheme.equals("whatsapp") || scheme.equals("intent")
                || scheme.equals("market")) {
            openExternal(uri);
            return true;
        }

        // بقیه schemeهای ناشناخته → WebView خودش امتحان کند
        return false;
    }

    /**
     * ✅ باز کردن لینک با اپ مناسب گوشی (تماس، پیامک، ایمیل، نقشه، مرورگر)
     */
    private void openExternal(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "برنامه‌ای برای باز کردن لینک پیدا نشد: " + uri);
            Toast.makeText(this, "برنامه‌ای برای باز کردن این لینک پیدا نشد", Toast.LENGTH_SHORT).show();
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

        // ✅ v4: کاهش شدید حساسیت Pull-to-Refresh
        //    پیش‌فرض ۶۴dp است و با کوچک‌ترین لمس به پایین فعال می‌شد؛
        //    حالا کشیدن حدود ۲۰۰dp لازم است (مقدار بر حسب پیکسل پاس داده می‌شود)
        try {
            int triggerPx = (int) (PULL_REFRESH_TRIGGER_DP
                    * getResources().getDisplayMetrics().density);
            swipeRefresh.setDistanceToTriggerSync(triggerPx);
        } catch (Exception e) {
            Log.w(TAG, "setDistanceToTriggerSync failed: " + e.getMessage());
        }

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
        // ✅ v4: اگر اسپلش هنوز باز است، بسته شود تا صفحه خطا دیده شود
        dismissSplashOverlay();
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

    // ============================================================
    // ✅ v4: مدیریت اسپلش، تایم‌اوت بارگذاری و نوار وضعیت
    // ============================================================

    /** آیا این آدرس مربوط به صفحه چت‌هاست؟ (Pull-to-Refresh آنجا غیرفعال می‌شود) */
    private boolean isChatUrl(String url) {
        if (url == null) return false;
        try {
            String path = Uri.parse(url).getPath();
            return path != null && path.contains("/chat");
        } catch (Exception e) {
            return false;
        }
    }

    /** شماره نسخه روی لایه اسپلش داخل MainActivity */
    private void setSplashOverlayVersion() {
        try {
            TextView tv = findViewById(R.id.splashOverlayVersion);
            if (tv == null) return;
            String v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (v != null && !v.isEmpty()) {
                tv.setText("EasyPod v" + v);
            }
        } catch (Exception ignored) {
        }
    }

    /** شروع/ریست تایمر ۲۰ ثانیه‌ای بارگذاری اولیه */
    private void startInitialLoadWatchdog() {
        if (splashHandler == null) return;
        splashHandler.removeCallbacksAndMessages(null);
        splashHandler.postDelayed(() -> {
            if (!initialPageLoaded && !splashHidden) {
                Log.w(TAG, "⏳ بارگذاری اولیه بیش از ۲۰ ثانیه طول کشید");
                showLoadTimeoutUI();
            }
        }, LOAD_TIMEOUT_MS);
    }

    private void cancelInitialLoadWatchdog() {
        if (splashHandler != null) splashHandler.removeCallbacksAndMessages(null);
    }

    private void showLoadTimeoutUI() {
        if (splashTimeoutBox != null) splashTimeoutBox.setVisibility(View.VISIBLE);
    }

    /** بستن اسپلش پس از حداقل ۳ ثانیه نمایش */
    private void dismissSplashOverlayWhenReady() {
        if (splashOverlay == null || splashHidden) return;
        long delay = SPLASH_MIN_DISPLAY_MS - (System.currentTimeMillis() - splashShownAt);
        if (delay > 0) {
            splashHandler.postDelayed(this::dismissSplashOverlay, delay);
        } else {
            dismissSplashOverlay();
        }
    }

    private void dismissSplashOverlay() {
        splashHidden = true;
        if (splashHandler != null) splashHandler.removeCallbacksAndMessages(null);
        if (splashTimeoutBox != null) splashTimeoutBox.setVisibility(View.GONE);
        if (splashOverlay != null) splashOverlay.setVisibility(View.GONE);
    }

    /** دکمه «تلاش مجدد» روی پیام تایم‌اوت */
    private void retryInitialLoad() {
        if (splashTimeoutBox != null) splashTimeoutBox.setVisibility(View.GONE);
        if (!isNetworkAvailable()) {
            Toast.makeText(MainActivity.this, "هنوز اینترنت وصل نیست", Toast.LENGTH_SHORT).show();
            return;
        }
        initialPageLoaded = false;
        startInitialLoadWatchdog();
        webView.reload();
    }

    /** ✅ v4: مخفی‌کردن نوار وضعیت — فقط نوار بالا؛ دکمه‌های پایین (Back/Home) باقی می‌مانند */
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
        } catch (Exception e) {
            Log.w(TAG, "hideStatusBar error: " + e.getMessage());
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideStatusBar();
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
