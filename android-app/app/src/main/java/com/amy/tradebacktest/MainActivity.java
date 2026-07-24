package com.amy.tradebacktest;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setVisibility(View.VISIBLE);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
            }

            @Override
            public void onReceivedError(
                    WebView view,
                    WebResourceRequest request,
                    WebResourceError error
            ) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    showConnectionError();
                }
            }
        });

        webView.loadUrl(BuildConfig.WEB_URL);
    }

    private void showConnectionError() {
        String html = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<style>body{margin:0;background:#090b10;color:#f4f7fb;font-family:sans-serif;display:grid;place-items:center;min-height:100vh;padding:24px;text-align:center}"
                + "button{border:0;border-radius:12px;padding:14px 20px;background:#f1b94a;color:#17120a;font-weight:700}</style></head>"
                + "<body><main><h2>Koneksi gagal</h2><p>Periksa internet atau status deployment Vercel.</p>"
                + "<button onclick=\"location.href='" + BuildConfig.WEB_URL + "'\">Coba lagi</button></main></body></html>";
        webView.loadDataWithBaseURL(BuildConfig.WEB_URL, html, "text/html", "UTF-8", null);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
        }
        super.onDestroy();
    }
}
