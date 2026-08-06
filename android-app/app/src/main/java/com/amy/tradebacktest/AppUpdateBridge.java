package com.amy.tradebacktest;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/** Native updater patterned after Amy FX: native manifest check, download, SHA-256, signing-cert check, installer. */
public final class AppUpdateBridge {
    private static final int MAX_MANIFEST_BYTES = 2 * 1024 * 1024;

    private final Activity activity;
    private final WebView webView;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean checking = new AtomicBoolean(false);
    private volatile boolean running;

    public AppUpdateBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
    }

    @JavascriptInterface
    public String getVersionInfo() {
        JSONObject json = new JSONObject();
        try {
            json.put("version_code", BuildConfig.VERSION_CODE);
            json.put("version_name", BuildConfig.VERSION_NAME);
            json.put("package_name", BuildConfig.APPLICATION_ID);
            json.put("signing_cert_sha256", installedCertificateSha256());
            json.put("can_install_packages", Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls());
        } catch (Exception ignored) {}
        return json.toString();
    }

    /**
     * Fetches the fixed release manifest through native HTTPS instead of JavaScript fetch.
     * Local file WebViews intentionally have universal file access disabled, so browser fetch
     * can fail with a misleading "Failed to fetch" even when Android has internet access.
     */
    @JavascriptInterface
    public void checkForUpdate(String manifestUrl) {
        if (manifestUrl == null || !manifestUrl.startsWith("https://")) {
            emitCheckError("URL manifest update tidak aman.");
            return;
        }
        if (!checking.compareAndSet(false, true)) {
            emitCheckError("Pemeriksaan update masih berjalan.");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject manifest = readManifest(manifestUrl);
                validateManifest(manifest);
                emit("onManifest", manifest);
            } catch (Exception error) {
                emitCheckError(safe(error));
            } finally {
                checking.set(false);
            }
        }, "app-update-check").start();
    }

    @JavascriptInterface
    public void startAppUpdate(String downloadUrl, String expectedSha256, String expectedCertificateSha256) {
        if (running) { emitError("Download update masih berjalan."); return; }
        if (downloadUrl == null || !downloadUrl.startsWith("https://")) { emitError("URL update tidak aman."); return; }
        running = true;
        cancelled.set(false);
        new Thread(() -> {
            File apk = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Tidak dapat membuat folder update.");
                apk = new File(dir, "Trading-Method-Lab-update.apk");
                download(downloadUrl, apk);
                String actualSha = sha256(apk);
                if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                        && !actualSha.equalsIgnoreCase(normalizeFingerprint(expectedSha256))) {
                    throw new SecurityException("SHA-256 APK tidak cocok. Update dibatalkan.");
                }
                String archiveCert = archiveCertificateSha256(apk);
                String expectedCert = normalizeFingerprint(expectedCertificateSha256);
                String installedCert = normalizeFingerprint(installedCertificateSha256());
                if (!expectedCert.isEmpty() && !archiveCert.equalsIgnoreCase(expectedCert)) {
                    throw new SecurityException("Sertifikat APK tidak sesuai manifest.");
                }
                if (!installedCert.isEmpty() && !archiveCert.equalsIgnoreCase(installedCert)) {
                    throw new SecurityException("Tanda tangan update berbeda dari aplikasi terpasang. Instalasi otomatis ditolak.");
                }
                emitProgress(100, apk.length(), apk.length(), "Terverifikasi. Membuka installer…");
                File finalApk = apk;
                activity.runOnUiThread(() -> install(finalApk));
            } catch (Exception error) {
                if (apk != null && apk.exists()) apk.delete();
                emitError(cancelled.get() ? "Update dibatalkan." : safe(error));
            } finally {
                running = false;
                cancelled.set(false);
            }
        }, "app-update-download").start();
    }

    @JavascriptInterface public void cancelAppUpdate() { cancelled.set(true); }

    private JSONObject readManifest(String manifestUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(manifestUrl).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/json,application/octet-stream");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", "Trading-Method-Lab/" + BuildConfig.VERSION_NAME);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) {
            connection.disconnect();
            throw new IllegalStateException("Server manifest HTTP " + status + ".");
        }
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), 32 * 1024);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_MANIFEST_BYTES) throw new IllegalStateException("Manifest update terlalu besar.");
                output.write(buffer, 0, read);
            }
            return new JSONObject(output.toString(StandardCharsets.UTF_8.name()));
        } finally {
            connection.disconnect();
        }
    }

    private static void validateManifest(JSONObject manifest) throws Exception {
        if (!manifest.has("version_code") || manifest.optInt("version_code", -1) < 1) {
            throw new IllegalStateException("version_code manifest tidak valid.");
        }
        if (manifest.optString("version_name", "").trim().isEmpty()) {
            throw new IllegalStateException("version_name manifest kosong.");
        }
        String downloadUrl = manifest.optString("download_url", "");
        if (!downloadUrl.startsWith("https://")) {
            throw new SecurityException("download_url manifest tidak aman.");
        }
        String sha = normalizeFingerprint(manifest.optString("sha256", ""));
        if (!sha.matches("[0-9a-f]{64}")) {
            throw new SecurityException("SHA-256 manifest tidak valid.");
        }
        String certificate = normalizeFingerprint(manifest.optString("signing_cert_sha256", ""));
        if (!certificate.matches("[0-9a-f]{64}")) {
            throw new SecurityException("Fingerprint sertifikat manifest tidak valid.");
        }
    }

    private void download(String downloadUrl, File target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(downloadUrl).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream");
        connection.setRequestProperty("User-Agent", "Trading-Method-Lab/" + BuildConfig.VERSION_NAME);
        int status = connection.getResponseCode();
        if (status < 200 || status >= 300) throw new IllegalStateException("Server update HTTP " + status + ".");
        long total = connection.getContentLengthLong();
        try (InputStream input = new BufferedInputStream(connection.getInputStream(), 128 * 1024);
             FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[128 * 1024];
            long downloaded = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (cancelled.get()) throw new InterruptedException("cancelled");
                output.write(buffer, 0, read);
                downloaded += read;
                int percent = total > 0 ? (int)Math.min(99, downloaded * 100 / total) : -1;
                emitProgress(percent, downloaded, total, "Mengunduh update…");
            }
        } finally {
            connection.disconnect();
        }
    }

    private void install(File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(settings);
                emitNeedsPermission();
                return;
            }
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".updateprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
            emit("onInstallerOpened", new JSONObject());
        } catch (Exception error) {
            emitError(safe(error));
        }
    }

    private String installedCertificateSha256() throws Exception {
        PackageInfo info = packageInfo(activity.getPackageName(), false, null);
        return certificateFrom(info);
    }

    private String archiveCertificateSha256(File apk) throws Exception {
        PackageInfo info = packageInfo(apk.getAbsolutePath(), true, apk);
        if (info == null) throw new SecurityException("APK update tidak dapat dibaca.");
        if (!activity.getPackageName().equals(info.packageName)) throw new SecurityException("Package update berbeda: " + info.packageName);
        return certificateFrom(info);
    }

    @SuppressWarnings("deprecation")
    private PackageInfo packageInfo(String name, boolean archive, File apk) throws Exception {
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        if (archive) return pm.getPackageArchiveInfo(name, flags);
        return pm.getPackageInfo(name, flags);
    }

    @SuppressWarnings("deprecation")
    private static String certificateFrom(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            signatures = info.signingInfo != null
                    ? (info.signingInfo.hasMultipleSigners() ? info.signingInfo.getApkContentsSigners() : info.signingInfo.getSigningCertificateHistory())
                    : null;
        } else signatures = info.signatures;
        if (signatures == null || signatures.length == 0) throw new SecurityException("Sertifikat aplikasi tidak ditemukan.");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hex(digest.digest(signatures[0].toByteArray()));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(new java.io.FileInputStream(file), 128 * 1024)) {
            byte[] buffer = new byte[128 * 1024]; int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) result.append(String.format(Locale.US, "%02x", b));
        return result.toString();
    }

    private static String normalizeFingerprint(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replace(":", "").replace(" ", "").trim();
    }

    private void emitProgress(int percent, long downloaded, long total, String message) {
        JSONObject json = new JSONObject();
        try { json.put("percent", percent); json.put("downloaded", downloaded); json.put("total", total); json.put("message", message); } catch (Exception ignored) {}
        emit("onProgress", json);
    }

    private void emitNeedsPermission() { emit("onNeedsInstallPermission", new JSONObject()); }
    private void emitCheckError(String message) { JSONObject json = new JSONObject(); try { json.put("message", message); } catch (Exception ignored) {} emit("onCheckError", json); }
    private void emitError(String message) { JSONObject json = new JSONObject(); try { json.put("message", message); } catch (Exception ignored) {} emit("onError", json); }
    private void emit(String callback, JSONObject payload) {
        String script = "window.NativeUpdater && window.NativeUpdater." + callback + "(" + payload.toString() + ");";
        activity.runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }
    private static String safe(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
