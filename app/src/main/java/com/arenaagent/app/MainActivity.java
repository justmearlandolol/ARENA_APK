package com.arenaagent.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String HOME_URL = "https://arena.ai/agent";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int STORAGE_PERMISSION_REQUEST = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> fileUploadCallback;
    private Runnable pendingStorageAction;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        webView.setBackgroundColor(Color.rgb(30, 30, 28));
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.addJavascriptInterface(new BlobDownloadBridge(), "AndroidDownloader");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> callback,
                    FileChooserParams params
            ) {
                if (fileUploadCallback != null) {
                    fileUploadCallback.onReceiveValue(null);
                }
                fileUploadCallback = callback;

                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                    return true;
                } catch (ActivityNotFoundException error) {
                    fileUploadCallback = null;
                    callback.onReceiveValue(null);
                    return false;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(
                    String url,
                    String userAgent,
                    String contentDisposition,
                    String mimeType,
                    long contentLength
            ) {
                if (url == null) {
                    showToast("URL download tidak valid");
                } else if (url.startsWith("blob:")) {
                    downloadBlob(url, contentDisposition, mimeType);
                } else if (url.startsWith("data:")) {
                    downloadData(url, contentDisposition, mimeType);
                } else if (url.startsWith("https://") || url.startsWith("http://")) {
                    downloadHttp(url, userAgent, contentDisposition, mimeType);
                } else {
                    showToast("Format download belum didukung: " + url);
                }
            }
        });

        if (savedInstanceState == null) {
            webView.loadUrl(HOME_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void downloadHttp(
            final String url,
            final String userAgent,
            final String contentDisposition,
            final String mimeType
    ) {
        withStoragePermission(new Runnable() {
            @Override
            public void run() {
                try {
                    String fileName = guessFileName(url, contentDisposition, mimeType);
                    DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                    request.setTitle(fileName);
                    request.setDescription("Download dari Arena Agent");
                    request.setMimeType(normalizeMime(mimeType));
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    );
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS,
                            fileName
                    );

                    if (userAgent != null && !userAgent.isEmpty()) {
                        request.addRequestHeader("User-Agent", userAgent);
                    }
                    String cookie = CookieManager.getInstance().getCookie(url);
                    if (cookie != null && !cookie.isEmpty()) {
                        request.addRequestHeader("Cookie", cookie);
                    }
                    String referer = webView.getUrl();
                    if (referer != null && referer.startsWith("http")) {
                        request.addRequestHeader("Referer", referer);
                    }

                    DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                    if (manager == null) {
                        throw new IllegalStateException("DownloadManager tidak tersedia");
                    }
                    manager.enqueue(request);
                    showToast("Download dimulai: " + fileName);
                } catch (Exception error) {
                    showToast("Download gagal: " + safeError(error));
                }
            }
        });
    }

    private void downloadBlob(
            final String url,
            final String contentDisposition,
            final String mimeType
    ) {
        final String fileName = guessFileName(url, contentDisposition, mimeType);

        withStoragePermission(new Runnable() {
            @Override
            public void run() {
                String script = "(function(){"
                        + "fetch(" + jsString(url) + ")"
                        + ".then(function(response){"
                        + "if(!response.ok){throw new Error('HTTP '+response.status);}"
                        + "return response.blob();"
                        + "})"
                        + ".then(function(blob){return new Promise(function(resolve,reject){"
                        + "var reader=new FileReader();"
                        + "reader.onload=function(){resolve(reader.result);};"
                        + "reader.onerror=function(){reject(reader.error);};"
                        + "reader.readAsDataURL(blob);"
                        + "});})"
                        + ".then(function(dataUrl){"
                        + "var base64=dataUrl.substring(dataUrl.indexOf(',')+1);"
                        + "AndroidDownloader.saveBase64(base64,"
                        + jsString(fileName) + "," + jsString(normalizeMime(mimeType)) + ");"
                        + "})"
                        + ".catch(function(error){"
                        + "AndroidDownloader.reportError(String(error));"
                        + "});"
                        + "})();";

                webView.evaluateJavascript(script, null);
                showToast("Menyiapkan download: " + fileName);
            }
        });
    }

    private void downloadData(
            final String url,
            final String contentDisposition,
            final String mimeType
    ) {
        withStoragePermission(new Runnable() {
            @Override
            public void run() {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            int separator = url.indexOf(',');
                            if (separator < 0) {
                                throw new IllegalArgumentException("Data URL tidak valid");
                            }

                            String metadata = url.substring(5, separator);
                            String payload = url.substring(separator + 1);
                            byte[] data;
                            if (metadata.toLowerCase(Locale.US).contains(";base64")) {
                                data = Base64.decode(payload, Base64.DEFAULT);
                            } else {
                                data = Uri.decode(payload).getBytes(StandardCharsets.UTF_8);
                            }

                            String actualMime = mimeType;
                            if ((actualMime == null || actualMime.isEmpty())
                                    && metadata.length() > 0 && !metadata.startsWith(";")) {
                                actualMime = metadata.split(";")[0];
                            }
                            String fileName = guessFileName(url, contentDisposition, actualMime);
                            String savedName = saveToDownloads(data, fileName, actualMime);
                            showToast("Download selesai: " + savedName);
                        } catch (Exception error) {
                            showToast("Download gagal: " + safeError(error));
                        }
                    }
                }, "arena-data-download").start();
            }
        });
    }

    private String saveToDownloads(byte[] data, String fileName, String mimeType) throws Exception {
        String safeName = sanitizeFileName(fileName);
        String safeMime = normalizeMime(mimeType);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, safeMime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            Uri collection = MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL_PRIMARY
            );
            Uri destination = resolver.insert(collection, values);
            if (destination == null) {
                throw new IllegalStateException("Tidak bisa membuat file di Downloads");
            }

            try {
                OutputStream output = resolver.openOutputStream(destination);
                if (output == null) {
                    throw new IllegalStateException("Tidak bisa membuka file tujuan");
                }
                try {
                    output.write(data);
                } finally {
                    output.close();
                }

                ContentValues completed = new ContentValues();
                completed.put(MediaStore.MediaColumns.IS_PENDING, 0);
                resolver.update(destination, completed, null, null);
                return safeName;
            } catch (Exception error) {
                resolver.delete(destination, null, null);
                throw error;
            }
        }

        File directory = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
        );
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Folder Downloads tidak bisa dibuat");
        }

        File destination = uniqueFile(directory, safeName);
        FileOutputStream output = new FileOutputStream(destination);
        try {
            output.write(data);
        } finally {
            output.close();
        }
        MediaScannerConnection.scanFile(
                this,
                new String[]{destination.getAbsolutePath()},
                new String[]{safeMime},
                null
        );
        return destination.getName();
    }

    private File uniqueFile(File directory, String fileName) {
        File candidate = new File(directory, fileName);
        if (!candidate.exists()) {
            return candidate;
        }

        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        return new File(
                directory,
                base + "-" + System.currentTimeMillis() + extension
        );
    }

    private String guessFileName(String url, String contentDisposition, String mimeType) {
        String fileName;
        try {
            fileName = url != null && url.startsWith("data:")
                    ? "arena-download-" + System.currentTimeMillis()
                    : URLUtil.guessFileName(url, contentDisposition, mimeType);
        } catch (Exception error) {
            fileName = "arena-download-" + System.currentTimeMillis();
        }
        fileName = sanitizeFileName(fileName);

        String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(
                normalizeMime(mimeType)
        );
        if (extension != null && !extension.isEmpty() && !fileName.contains(".")) {
            fileName += "." + extension;
        }
        return fileName;
    }

    private String sanitizeFileName(String fileName) {
        String result = fileName == null ? "" : fileName.trim();
        result = result.replaceAll("[\\\\/:*?\"<>|]", "_");
        return result.isEmpty()
                ? "arena-download-" + System.currentTimeMillis()
                : result;
    }

    private String normalizeMime(String mimeType) {
        return mimeType == null || mimeType.trim().isEmpty()
                ? "application/octet-stream"
                : mimeType.trim();
    }

    private String jsString(String value) {
        return JSONObject.quote(value == null ? "" : value);
    }

    private String safeError(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private void withStoragePermission(Runnable action) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            pendingStorageAction = action;
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    STORAGE_PERMISSION_REQUEST
            );
        } else {
            action.run();
        }
    }

    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing() && !isDestroyed()) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    public final class BlobDownloadBridge {
        @JavascriptInterface
        public void saveBase64(String base64, String fileName, String mimeType) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                String savedName = saveToDownloads(data, fileName, mimeType);
                showToast("Download selesai: " + savedName);
            } catch (Exception error) {
                showToast("Download gagal: " + safeError(error));
            }
        }

        @JavascriptInterface
        public void reportError(String message) {
            showToast("Download gagal: " + message);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == STORAGE_PERMISSION_REQUEST) {
            Runnable action = pendingStorageAction;
            pendingStorageAction = null;
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && action != null) {
                action.run();
            } else {
                showToast("Izin penyimpanan diperlukan untuk download");
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == FILE_CHOOSER_REQUEST && fileUploadCallback != null) {
            Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
            fileUploadCallback.onReceiveValue(result);
            fileUploadCallback = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        pendingStorageAction = null;
        if (fileUploadCallback != null) {
            fileUploadCallback.onReceiveValue(null);
            fileUploadCallback = null;
        }
        if (webView != null) {
            webView.setDownloadListener(null);
            webView.removeJavascriptInterface("AndroidDownloader");
            webView.destroy();
        }
        super.onDestroy();
    }
  }
                
