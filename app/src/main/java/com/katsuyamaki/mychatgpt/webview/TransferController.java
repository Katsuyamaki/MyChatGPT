package com.katsuyamaki.mychatgpt.webview;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

/**
 * Owns Android-side outbound transfer and download I/O.
 *
 * Incoming share auto-attach and file-chooser/camera handoff remain in the
 * Activity during this bounded first pass; those move only after this I/O
 * extraction validates independently.
 */
public final class TransferController {

    private static final String TAG = "MyChatGPTTransfer";
    public static final int REQUEST_STORAGE_PERM = 1003;

    public interface Host {
        WebView getMainWebView();
    }

    private final Activity activity;
    private final Host host;

    private String[] pendingDownload;

    private volatile boolean blobDownloadInFlight;
    private volatile String pendingBlobFilename;
    private volatile String pendingBlobMime;

    private final Object blobSaveLock = new Object();
    private volatile long lastBlobSaveAt;
    private volatile int lastBlobSaveLen;

    private final Object blobChunksLock = new Object();
    private final java.util.HashMap<String, StringBuilder> blobChunks = new java.util.HashMap<>();

    private volatile long blobBridgeSuccessAt;

    public TransferController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    public void onBlobChunk(String name, String mime, int index, int total, String data) {
        String b64 = null;
        synchronized (blobChunksLock) {
            StringBuilder sb = blobChunks.get(name);
            if (sb == null) {
                sb = new StringBuilder();
                blobChunks.put(name, sb);
            }
            sb.append(data);
            if (index == total - 1) {
                b64 = sb.toString();
                blobChunks.remove(name);
            }
        }
        if (b64 != null) {
            final String payload = b64;
            activity.runOnUiThread(() ->
                    handleBlobDownload(name, "data:" + mime + ";base64," + payload));
        }
    }

    public boolean onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE_PERM) return false;

        boolean granted = grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        String[] download = pendingDownload;
        pendingDownload = null;
        if (granted && download != null) {
            downloadWithCookies(download[0], download[1], download[2], download[3]);
        } else if (download != null) {
            Toast.makeText(activity,
                    "Download cancelled — storage permission was denied",
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    public void destroy() {
        pendingDownload = null;
        blobDownloadInFlight = false;
        pendingBlobFilename = null;
        pendingBlobMime = null;
        synchronized (blobChunksLock) {
            blobChunks.clear();
        }
    }

    /** Share text (and/or a URL) through the system share sheet. */
    public void shareTextNative(String text, String url) {
        Log.i(TAG, "shareText bridge reached");
        try {
            StringBuilder sb = new StringBuilder();
            if (text != null && !text.isEmpty()) sb.append(text);
            if (url != null && !url.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(url);
            }
            if (sb.length() == 0) return;
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, sb.toString());
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(Intent.createChooser(share, "Share"));
        } catch (Exception e) {
            Log.e(TAG, "shareTextNative failed", e);
            Toast.makeText(activity, "Share failed", Toast.LENGTH_SHORT).show();
        }
    }

    /** Share a single file (decoded from a data URL) through the system sheet. */
    public void shareFileNative(String title, String dataUrl, String fileName, String mime) {
        Log.i(TAG, "shareFile bridge reached: " + fileName);
        new Thread(() -> {
            try {
                if (dataUrl == null || !dataUrl.startsWith("data:")) return;
                int comma = dataUrl.indexOf(',');
                if (comma < 0) return;
                String realMime = mime != null && mime.contains("/") ? mime : guessDataUrlMime(dataUrl);
                String ext = extensionForMime(realMime);
                String name = sanitizeSharedFileName(
                        fileName != null && !fileName.isEmpty() ? fileName : "shared_file");
                if (ext != null && !name.toLowerCase(Locale.ROOT).endsWith("." + ext)) {
                    name += "." + ext;
                }
                byte[] bytes = android.util.Base64.decode(dataUrl.substring(comma + 1),
                        android.util.Base64.DEFAULT);
                File outFile = new File(activity.getCacheDir(), name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                fos.write(bytes);
                fos.flush();
                fos.close();
                Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                        activity.getPackageName() + ".fileprovider", outFile);
                activity.runOnUiThread(() -> {
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(realMime != null ? realMime : "application/octet-stream");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        if (title != null && !title.isEmpty()) {
                            share.putExtra(Intent.EXTRA_TEXT, title);
                        }
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(Intent.createChooser(share, "Share"));
                    } catch (Exception e) {
                        Log.e(TAG, "shareFileNative intent failed", e);
                        Toast.makeText(activity, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "shareFileNative failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String guessDataUrlMime(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, Math.max(comma, dataUrl.length()));
            int semi = meta.indexOf(';');
            return semi > 0 ? meta.substring(0, semi) : "application/octet-stream";
        } catch (Throwable t) {
            return "application/octet-stream";
        }
    }


    public void downloadAndShareImageFile(String url) {
        final String cookies = CookieManager.getInstance().getCookie(url);
        WebView mainWebView = host.getMainWebView();
        final String userAgent = mainWebView != null
                ? mainWebView.getSettings().getUserAgentString()
                : ChatGptSiteContract.MOBILE_USER_AGENT;

        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            java.io.OutputStream out = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Referer", ChatGptSiteContract.MAIN_URL);
                conn.setRequestProperty("Accept", "image/*,*/*");

                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    final int c = code;
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Failed: HTTP " + c, Toast.LENGTH_LONG).show());
                    return;
                }

                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) {
                    mime = mime.split(";")[0].trim();
                }
                if (mime == null || !mime.startsWith("image/")) {
                    mime = "image/png";  // default
                }
                String ext = extensionForMime(mime);
                if (ext == null) ext = "png";

                File outFile = new File(activity.getCacheDir(),
                        "shared_image_" + System.currentTimeMillis() + "." + ext);
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                final File finalOutFile = outFile;
                final String finalMime = mime;
                activity.runOnUiThread(() -> shareFile(finalOutFile, finalMime));
            } catch (Exception e) {
                Log.e(TAG, "downloadAndShareImageFile failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void shareFile(File file, String mime) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(Intent.createChooser(share, "Share"));
        } catch (Exception e) {
            Log.e(TAG, "shareFile failed", e);
            Toast.makeText(activity, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    public void downloadImageToDownloads(String url) {
        final String cookies = CookieManager.getInstance().getCookie(url);
        WebView mainWebView = host.getMainWebView();
        final String userAgent = mainWebView != null
                ? mainWebView.getSettings().getUserAgentString()
                : ChatGptSiteContract.MOBILE_USER_AGENT;
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            java.io.OutputStream out = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Referer", ChatGptSiteContract.MAIN_URL);
                conn.setRequestProperty("Accept", "image/*,*/*");
                // Byte-exact image bytes — see saveFileWithCookies.
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "Failed: HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }
                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) mime = mime.split(";")[0].trim();
                // Interstitial guard: never save an HTML error/login page as
                // an image — fail loudly instead (link expired / auth bounce).
                if (mime != null && mime.toLowerCase().startsWith("text/html")) {
                    Log.e(TAG, "Image download got HTML interstitial: " + url);
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Failed: the server returned a web page",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                String ext = extensionForMime(mime);
                if (ext == null) ext = "png";
                String fileName = "chatgpt_image_" + System.currentTimeMillis() + "." + ext;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentResolver resolver = activity.getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri != null) {
                        out = resolver.openOutputStream(itemUri);
                    }
                } else {
                    File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dl.exists()) dl.mkdirs();
                    out = new java.io.FileOutputStream(new File(dl, fileName));
                }
                if (out == null) {
                    activity.runOnUiThread(() -> Toast.makeText(activity, "Failed to save", Toast.LENGTH_LONG).show());
                    return;
                }
                in = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                final String fn = fileName;
                activity.runOnUiThread(() -> Toast.makeText(activity, "Saved to Download/" + fn, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "downloadImageToDownloads failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    public void setupDownloads(WebView webView) {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Log.i(TAG, "Download requested: " + url + " (mime: " + mimetype + ")");
            if (url == null) return;
            if (url.startsWith("data:")) {
                // Data URLs carry the whole payload inline — save directly,
                // no second fetch needed.
                String filename = guessFilenameFromDownload(url, contentDisposition, mimetype);
                handleBlobDownload(filename, url);
                return;
            }
            if (url.startsWith("blob:")) {
                // Fallback for downloads the JS bridge did NOT already
                // deliver (store miss + sync XHR miss + async miss). When the
                // bridge just saved successfully, skip this dead-URL fetch so
                // its failure toasts do not fire pointlessly after a good save.
                if (SystemClock.elapsedRealtime() - blobBridgeSuccessAt < 8000) {
                    Log.i(TAG, "blob fallback suppressed");
                    return;
                }
                downloadBlobUrl(webView, url, contentDisposition, mimetype);
                return;
            }
            downloadWithCookies(url, userAgent, contentDisposition, mimetype);
        });
    }

    /**
     * Download a blob: URL by fetching it via in-page JavaScript and passing
     * the base64-encoded bytes back to native code via the JS bridge. Works
     * even after the site called revokeObjectURL() because our injected hook
     * delays the actual revocation.
     */
    private void downloadBlobUrl(WebView webView, String blobUrl, String contentDisposition, String mimetype) {
        Toast.makeText(activity, "Downloading...", Toast.LENGTH_SHORT).show();
        // blob: URLs carry no usable filename and contentDisposition is
        // usually null — use a timestamp name; the real extension is fixed
        // from the payload's MIME type in handleBlobDownload.
        final String filename;
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            filename = guessFilenameFromDownload(blobUrl, contentDisposition, mimetype);
        } else {
            filename = "download_" + System.currentTimeMillis();
        }
        final String finalMime = mimetype != null ? mimetype : "application/octet-stream";
        pendingBlobFilename = filename;
        pendingBlobMime = finalMime;
        blobDownloadInFlight = true;
        String js = ChatGptSiteContract.buildBlobFetchJs(blobUrl);
        webView.evaluateJavascript(js, null);
        // Safety net: if the bridge never answers (page navigated away,
        // renderer killed, ...) give up quietly after 30 seconds.
        webView.postDelayed(() -> {
            if (blobDownloadInFlight) {
                blobDownloadInFlight = false;
                pendingBlobFilename = null;
                pendingBlobMime = null;
                Toast.makeText(activity,
                        "Download did not complete. Hold down on the image to open the share & download menu.",
                        Toast.LENGTH_LONG).show();
            }
        }, ChatGptSiteContract.BLOB_DOWNLOAD_TIMEOUT_MS);
    }

    /** Called by WebAppInterface.onBlobResult with a data-URL payload. */
    public void handleBlobResult(String dataUrl) {
        if (!blobDownloadInFlight || dataUrl == null) return;
        blobDownloadInFlight = false;
        String filename = pendingBlobFilename;
        pendingBlobFilename = null;
        pendingBlobMime = null;
        handleBlobDownload(filename, dataUrl);
    }

    /** Called by WebAppInterface.onBlobFailed when the in-page fetch failed. */
    public void handleBlobFailed() {
        if (!blobDownloadInFlight) return;
        blobDownloadInFlight = false;
        pendingBlobFilename = null;
        pendingBlobMime = null;
        Toast.makeText(activity,
                "Can't download directly. Hold down on the image to open the share & download menu.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Direct blob/data download: payload arrives as a data URL
     * ("data:mime;base64,...") captured while the blob was still alive,
     * together with a suggested filename. Fixes the file extension from the
     * payload's real MIME type (PDF/Word/Markdown exports etc.).
     */
    public void handleBlobDownload(String name, String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return;
        // Dedup: the anchor-click hook and the DownloadListener fallback can
        // both deliver the same blob (belt and suspenders). Skip a save if an
        // identical payload arrived moments ago.
        long now = SystemClock.elapsedRealtime();
        synchronized (blobSaveLock) {
            if (now - lastBlobSaveAt < 4000 && dataUrl.length() == lastBlobSaveLen) {
                Log.i(TAG, "blob duplicate skipped: " + name);
                return;
            }
            lastBlobSaveAt = now;
            lastBlobSaveLen = dataUrl.length();
        }
        Log.i(TAG, "blob download reached: " + name);
        blobBridgeSuccessAt = SystemClock.elapsedRealtime();
        String filename = sanitizeFilename(name != null && !name.isEmpty()
                ? name : ("download_" + System.currentTimeMillis()));
        String mime = guessDataUrlMime(dataUrl);
        String ext = extensionForMime(mime);
        if (ext != null && !filename.toLowerCase(Locale.ROOT)
                .endsWith("." + ext.toLowerCase(Locale.ROOT))) {
            int dot = filename.lastIndexOf('.');
            // Only strip an existing (wrong) extension when it is very short
            // or absent — never mangle names like "report.v2".
            if (dot > 0 && filename.length() - dot <= 5) {
                filename = filename.substring(0, dot);
            }
            filename += "." + ext;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return;
        String b64 = dataUrl.substring(comma + 1);
        Toast.makeText(activity, "Downloading " + filename + "...", Toast.LENGTH_SHORT).show();
        saveBlobBase64(b64, filename, mime);
    }

    /** Common MIME → extension map (MimeTypeMap misses docx/md sometimes). */
    private static String extensionForMime(String mime) {
        if (mime == null) return null;
        String m = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "application/pdf": return "pdf";
            case "application/msword": return "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "text/markdown": case "text/x-markdown": return "md";
            case "text/plain": return "txt";
            case "text/html": return "html";
            case "text/csv": return "csv";
            case "application/json": return "json";
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/svg+xml": return "svg";
            default: {
                String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
                return e != null ? e : null;
            }
        }
    }

    private void saveBlobBase64(String b64, String fileName, String mime) {
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentResolver resolver = activity.getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Failed to create download entry", Toast.LENGTH_LONG).show());
                        return;
                    }
                    java.io.OutputStream out = resolver.openOutputStream(itemUri);
                    if (out == null) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Failed to open output stream", Toast.LENGTH_LONG).show());
                        return;
                    }
                    out.write(bytes);
                    out.flush();
                    out.close();
                } else {
                    File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dl.exists()) dl.mkdirs();
                    new java.io.FileOutputStream(new File(dl, fileName)).write(bytes);
                }
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Saved to Download/" + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "saveBlobBase64 failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void downloadWithCookies(String url, String userAgent, String contentDisposition,
                                     String mimetype) {
        final String fileName = guessFilenameFromDownload(url, contentDisposition, mimetype);
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String finalMimetype = mimetype != null ? mimetype : "application/octet-stream";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(activity, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Remember the download and re-issue it once the permission is
            // granted — previously this request dead-ended and the download
            // was silently dropped.
            pendingDownload = new String[]{
                    url, userAgent != null ? userAgent : "", contentDisposition, mimetype};
            ActivityCompat.requestPermissions(activity,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERM);
            return;
        }

        Toast.makeText(activity, "Downloading: " + fileName, Toast.LENGTH_SHORT).show();
        saveFileWithCookies(url, userAgent, cookies, fileName, finalMimetype);
    }

    private void saveFileWithCookies(String url, String userAgent, String cookies,
                                     String fileName, String mimetype) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream input = null;
            java.io.OutputStream output = null;
            try {
                URL urlObj = new URL(url);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : ChatGptSiteContract.MOBILE_USER_AGENT);
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Referer", ChatGptSiteContract.MAIN_URL);
                // Byte-exact downloads (pattern from the AI Studio webclient):
                // forbid transparent gzip so a binary can never be saved with
                // a compression wrapper wedged around it.
                conn.setRequestProperty("Accept-Encoding", "identity");

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    final int code = responseCode;
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Download failed: HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }

                String realMime = conn.getContentType();
                if (realMime != null && realMime.contains("/")) {
                    realMime = realMime.split(";")[0].trim();
                } else {
                    realMime = mimetype;
                }

                // Post-redirect filename (AI Studio pattern): the original
                // name was guessed from the pre-redirect URL/disposition the
                // DownloadListener saw. If the chain bounced through
                // redirects, the FINAL response's Content-Disposition is
                // authoritative — prefer it when present.
                String effectiveName = fileName;
                String headerName = filenameFromDisposition(
                        conn.getHeaderField("Content-Disposition"));
                if (headerName != null && !headerName.isEmpty()) {
                    String cleaned = sanitizeFilename(headerName);
                    if (!cleaned.isEmpty() && !"shared_file".equals(cleaned)) {
                        effectiveName = cleaned;
                    }
                }

                // Interstitial guard (AI Studio pattern): a text/html answer
                // for a non-HTML download means the link bounced to an
                // error/login page — saving it would write an HTML page
                // named like the file. Fail loudly instead.
                if (realMime != null && realMime.toLowerCase().startsWith("text/html")
                        && !effectiveName.toLowerCase().endsWith(".html")
                        && !effectiveName.toLowerCase().endsWith(".htm")) {
                    final String fn = effectiveName;
                    Log.e(TAG, "Download got HTML interstitial for " + fn);
                    activity.runOnUiThread(() -> Toast.makeText(activity,
                            "Download failed: the server returned a web page (link may have expired)",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                input = conn.getInputStream();
                final String finalRealMime = realMime;
                // Legacy (<Q) target file — hoisted so the post-save
                // MediaScanner pass can see it; stays null on Q+.
                File outFile = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String mimeExtension = extensionForMime(realMime);
                    String displayName = effectiveName;
                    String effectiveMime = realMime;
                    if (mimeExtension != null && !mimeExtension.isEmpty()) {
                        int lastDot = displayName.lastIndexOf('.');
                        if (lastDot > 0) {
                            displayName = displayName.substring(0, lastDot);
                        }
                        displayName += "." + mimeExtension;
                    } else {
                        effectiveMime = null;
                    }

                    android.content.ContentResolver resolver = activity.getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName);
                    if (effectiveMime != null) {
                        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, effectiveMime);
                    }
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);

                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Failed to create download entry", Toast.LENGTH_LONG).show());
                        return;
                    }
                    output = resolver.openOutputStream(itemUri);
                    if (output == null) {
                        activity.runOnUiThread(() -> Toast.makeText(activity,
                                "Failed to open output stream", Toast.LENGTH_LONG).show());
                        return;
                    }
                } else {
                    File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    outFile = new File(downloadsDir, effectiveName);
                    output = new java.io.FileOutputStream(outFile);
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long total = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    total += bytesRead;
                }
                output.flush();
                Log.i(TAG, "Downloaded " + total + " bytes (" + effectiveName + ")");

                // Legacy (<Q) writes land in the public Downloads dir outside
                // MediaStore — scan them so Files/Gallery apps see the file
                // immediately instead of after the next media sweep.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && outFile != null) {
                    try {
                        android.media.MediaScannerConnection.scanFile(activity,
                                new String[]{outFile.getAbsolutePath()},
                                new String[]{realMime}, null);
                    } catch (Throwable t) {
                        Log.w(TAG, "MediaScanner failed", t);
                    }
                }

                final String finalFileName = effectiveName;
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Saved to Download/" + finalFileName,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) {}
                if (output != null) try { output.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * Extract just the filename from a Content-Disposition header, or null
     * when the header carries none. Lighter than guessFilenameFromDownload:
     * no URL/mime fallbacks — callers keep their existing name when null.
     * RFC 5987 filename* wins over the plain filename token; both forms are
     * percent-decoded and '+' is protected from URLDecoder.
     */
    private static String filenameFromDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) return null;
        try {
            java.util.regex.Matcher star = java.util.regex.Pattern.compile(
                    "filename\\*=\\s*(?:utf-8|iso-8859-1)?''([^;]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (star.find()) {
                String name = star.group(1).trim().replace("+", "%2B");
                return java.net.URLDecoder.decode(name, "UTF-8");
            }
            java.util.regex.Matcher plain = java.util.regex.Pattern.compile(
                    "filename=\\s*[\"']?([^\"';]+)[\"']?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (plain.find()) {
                String name = plain.group(1).trim();
                if (name.contains("%")) {
                    try {
                        name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                    } catch (Exception ignored) {}
                }
                return name;
            }
        } catch (Exception e) {
            Log.w(TAG, "filenameFromDisposition failed", e);
        }
        return null;
    }

    private String guessFilenameFromDownload(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            // Robust RFC-6266 filename extraction. Previous versions used a
            // hand-rolled substring parser that left a trailing `"` in the
            // result when the disposition had trailing parameters
            // (e.g. `filename="x.pdf"; size=123`). A regex that captures
            // everything between the (optional) opening and closing quotes
            // is bulletproof.
            //
            // RFC 5987 `filename*` takes precedence when present, and its
            // value is percent-encoded — decode it (previous versions saved
            // the raw percent signs, e.g. "informe%202026.pdf").
            java.util.regex.Matcher star = java.util.regex.Pattern.compile(
                    "filename\\*=\\s*(?:utf-8|iso-8859-1)?''([^;]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (star.find()) {
                String name = star.group(1).trim();
                // '+' is literal in RFC 5987 — protect it from URLDecoder,
                // which would turn it into a space.
                try {
                    name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {
                }
                name = sanitizeFilename(name);
                if (!name.isEmpty()) {
                    return name;
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "filename=(?:UTF-8'')?\"?([^\";]+)\"?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) {
                    return sanitizeFilename(name);
                }
            }
        }
        return sanitizeFilename(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
    }

    private static String sanitizeFilename(String name) {
        name = name.replaceAll("[/\\\\]", "_").trim();
        if (name.length() > 200) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ext = name.substring(dot);
                name = name.substring(0, 200 - ext.length()) + ext;
            } else {
                name = name.substring(0, 200);
            }
        }
        return name.isEmpty() ? "download" : name;
    }

    /** Sanitize a provider-supplied display name for use inside activity.getCacheDir(). */
    public static String sanitizeSharedFileName(String name) {
        String n = sanitizeFilename(name);
        while (n.startsWith(".")) {
            n = n.substring(1);
        }
        return n.isEmpty() ? "shared_file" : n;
    }


}
