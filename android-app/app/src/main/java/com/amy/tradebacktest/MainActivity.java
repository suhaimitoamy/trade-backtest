package com.amy.tradebacktest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {
    private static final int FILE_CHOOSER_REQUEST = 1001;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private LocalZipBacktestBridge presetBridge;
    private AdvancedMethodBacktestBridge methodBuilderBridge;
    private AppUpdateBridge appUpdateBridge;

    @Override
    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setVisibility(View.VISIBLE);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        presetBridge = new LocalZipBacktestBridge(this, webView);
        methodBuilderBridge = new AdvancedMethodBacktestBridge(this, webView);
        appUpdateBridge = new AppUpdateBridge(this, webView);
        webView.addJavascriptInterface(presetBridge, "AndroidBacktest");
        webView.addJavascriptInterface(methodBuilderBridge, "AndroidMethodBuilder");
        webView.addJavascriptInterface(appUpdateBridge, "AndroidUpdater");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/zip", "application/x-zip-compressed", "text/csv", "text/plain", "application/octet-stream"
                });
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(intent, FILE_CHOOSER_REQUEST); return true; }
                catch (Exception error) { filePathCallback = null; return false; }
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file:///android_asset/index.html")) {
                    String loader = "(function(){"
                            + "if(document.getElementById('tml-mobile-ui-patch'))return;"
                            + "var s=document.createElement('script');"
                            + "s.id='tml-mobile-ui-patch';"
                            + "s.src='file:///android_asset/mobile-ui-patch.js';"
                            + "s.onerror=function(){var e=document.getElementById('status');if(e)e.textContent='Patch UI mobile gagal dimuat.';};"
                            + "document.body.appendChild(s);"
                            + "})();";
                    view.evaluateJavascript(loader, null);
                }
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) showLocalPageError();
            }
        });

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || filePathCallback == null) return;
        List<Uri> selected = new ArrayList<>();
        if (resultCode == Activity.RESULT_OK && data != null) {
            ClipData clips = data.getClipData();
            if (clips != null) {
                for (int i = 0; i < clips.getItemCount(); i++) {
                    Uri uri = clips.getItemAt(i).getUri(); if (uri != null) selected.add(uri);
                }
            } else if (data.getData() != null) selected.add(data.getData());
            for (Uri uri : selected) {
                try { getContentResolver().takePersistableUriPermission(uri, data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION); }
                catch (Exception ignored) {}
            }
        }
        Uri[] result = selected.isEmpty() ? null : selected.toArray(new Uri[0]);
        if (!selected.isEmpty()) {
            presetBridge.setSelectedFiles(selected);
            methodBuilderBridge.setSelectedFiles(selected);
        }
        filePathCallback.onReceiveValue(result);
        filePathCallback = null;
    }

    private void showLocalPageError() {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:#080b12;color:#f4f7fb;font-family:sans-serif;display:grid;place-items:center;min-height:100vh;padding:24px;text-align:center}button{border:0;border-radius:12px;padding:14px 20px;background:#f0b84b;color:#17120a;font-weight:700}</style></head>"
                + "<body><main><h2>Halaman aplikasi gagal dimuat</h2><p>Instal ulang APK dari channel update resmi.</p><button onclick=\"location.href='file:///android_asset/index.html'\">Muat ulang</button></main></body></html>";
        webView.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "UTF-8", null);
    }

    @Override public void onBackPressed() { if (webView != null && webView.canGoBack()) webView.goBack(); else super.onBackPressed(); }

    @Override protected void onDestroy() {
        if (filePathCallback != null) { filePathCallback.onReceiveValue(null); filePathCallback = null; }
        if (presetBridge != null) presetBridge.cancel();
        if (methodBuilderBridge != null) methodBuilderBridge.cancel();
        if (appUpdateBridge != null) appUpdateBridge.cancelAppUpdate();
        if (webView != null) {
            webView.stopLoading();
            webView.removeJavascriptInterface("AndroidBacktest");
            webView.removeJavascriptInterface("AndroidMethodBuilder");
            webView.removeJavascriptInterface("AndroidUpdater");
            webView.destroy();
        }
        super.onDestroy();
    }
}
