package com.prismflow.downloader;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import android.util.Log;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import com.yausername.ffmpeg.FFmpeg;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;

public class MainActivity extends Activity {
    private static final String TAG = "PrismFlow";
    private WebView webView;
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean engineReady = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new DownloadBridge(this), "AndroidDownload");
        webView.loadUrl("file:///android_asset/index.html");
        if (android.os.Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 41);
        }

        engineExecutor.execute(() -> {
            try {
                YoutubeDL.getInstance().init(getApplication());
                FFmpeg.getInstance().init(getApplication());
                engineReady = true;
                sendJs("PrismEngine.onReady(true,'ভিডিও ইঞ্জিন প্রস্তুত')");
            } catch (Exception e) {
                Log.e(TAG, "yt-dlp initialization failed", e);
                sendJs("PrismEngine.onReady(false,'ইঞ্জিন চালু হয়নি: লাইব্রেরি/ডিভাইস সমর্থন পরীক্ষা করুন')");
            }
        });
    }

    private void sendJs(String js) {
        runOnUiThread(() -> {
            if (webView != null) webView.evaluateJavascript(js, null);
        });
    }

    private class DownloadBridge {
        private final Context context;
        DownloadBridge(Context c) { context = c; }

        @JavascriptInterface public boolean isEngineReady() { return engineReady; }

        @JavascriptInterface public void downloadMedia(String rawUrl, String quality, boolean audioOnly) {
            final String url = rawUrl == null ? "" : rawUrl.trim();
            final String q = quality == null ? "720" : quality.trim();
            if (url.isEmpty() || !(url.startsWith("https://") || url.startsWith("http://"))) {
                sendJs("PrismEngine.onError('একটি বৈধ HTTP/HTTPS লিংক দিন')"); return;
            }
            if (!engineReady) {
                sendJs("PrismEngine.onError('ভিডিও ইঞ্জিন এখনো প্রস্তুত নয়; একটু পরে চেষ্টা করুন')"); return;
            }
            engineExecutor.execute(() -> {
                try {
                    File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    File folder = new File(downloads, "PRISM_FLOW");
                    if (!folder.exists() && !folder.mkdirs()) throw new Exception("Downloads/PRISM_FLOW ফোল্ডার তৈরি হয়নি");
                    String template = new File(folder, "%(title).180B_%(id)s.%(ext)s").getAbsolutePath();
                    YoutubeDLRequest request = new YoutubeDLRequest(url);
                    request.addOption("--no-playlist");
                    request.addOption("--newline");
                    request.addOption("--no-mtime");
                    request.addOption("--no-warnings");
                    request.addOption("--socket-timeout", "20");
                    request.addOption("--retries", "3");
                    request.addOption("-o", template);
                    if (audioOnly) {
                        request.addOption("-x");
                        request.addOption("--audio-format", "mp3");
                        request.addOption("--audio-quality", "0");
                    } else {
                        String format;
                        if ("best".equalsIgnoreCase(q)) format = "bestvideo+bestaudio/best";
                        else {
                            int height;
                            try { height = Integer.parseInt(q); } catch (Exception ex) { height = 720; }
                            if (height < 144 || height > 4320) height = 720;
                            format = "bestvideo[height<=" + height + "]+bestaudio/best[height<=" + height + "]/best";
                        }
                        request.addOption("-f", format);
                        request.addOption("--merge-output-format", "mp4");
                    }
                    sendJs("PrismEngine.onStart(" + jsString(url) + ")");
                    YoutubeDLResponse response = YoutubeDL.getInstance().execute(request, "prism-" + System.currentTimeMillis(), new Function3<Float, Long, String, Unit>() {
                        @Override public Unit invoke(Float progress, Long eta, String line) {
                            int p = progress == null ? 0 : Math.max(0, Math.min(100, progress.intValue()));
                            long seconds = eta == null ? 0L : Math.max(0L, eta.longValue());
                            sendJs("PrismEngine.onProgress(" + p + "," + seconds + ")");
                            return Unit.INSTANCE;
                        }
                    });
                    Log.i(TAG, "yt-dlp finished: " + response.getOut());
                    sendJs("PrismEngine.onComplete(" + jsString("ডাউনলোড শেষ। Downloads/PRISM_FLOW ফোল্ডারে দেখুন") + ")");
                } catch (Exception e) {
                    Log.e(TAG, "Download failed", e);
                    String msg = e.getMessage() == null ? "ডাউনলোড ব্যর্থ হয়েছে" : e.getMessage();
                    if (msg.length() > 180) msg = msg.substring(0, 180);
                    sendJs("PrismEngine.onError(" + jsString(msg) + ")");
                }
            });
        }

        @JavascriptInterface public void downloadDirect(String rawUrl, String filename) {
            // All links, including direct media links, are routed through yt-dlp now.
            downloadMedia(rawUrl, "720", false);
        }
    }

    private String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ").replace("\r", " ") + "'";
    }

    @Override public void onDestroy() {
        super.onDestroy();
        engineExecutor.shutdownNow();
        if (webView != null) { webView.removeJavascriptInterface("AndroidDownload"); webView.destroy(); webView = null; }
    }
}
