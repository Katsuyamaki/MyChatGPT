package com.katsuyamaki.mychatgpt;

import com.katsuyamaki.mychatgpt.BuildConfig;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.LinearInterpolator;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;
import androidx.webkit.WebViewCompat;

import com.katsuyamaki.mychatgpt.webview.CrashTracker;
import com.katsuyamaki.mychatgpt.webview.MainWebViewController;
import com.katsuyamaki.mychatgpt.webview.PopupAuthController;
import com.katsuyamaki.mychatgpt.webview.TransferController;
import com.katsuyamaki.mychatgpt.webview.WebViewManagerDialog;
import com.katsuyamaki.mychatgpt.webview.WebViewUtil;
import com.katsuyamaki.mychatgpt.webview.WelcomeDialog;
import com.katsuyamaki.mychatgpt.site.ChatGptSiteContract;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "MyChatGPTApp";
    private static final String PREFS_NAME = "webgpt_prefs";

    private static final int REQUEST_FILE_CHOOSER = 54321;
    private static final int REQUEST_MEDIA_PERM = 1004;
    private static final int REQUEST_CAMERA_PERM = 1005;

    WebView webview;
    ViewGroup rootLayout;
    View loadingOverlay;
    ImageView loadingLogo;
    LinearProgressIndicator loadingProgressBar;
    AnimatorSet loadingLogoAnim;
    /** Snapshot overlay shown on top of rootLayout during resume to mask
     *  the brief GPU-surface-recreation black flash. Captured when the
     *  activity loses window focus (just before onPause), faded out 300ms
     *  after the activity regains focus. Fixes the "brief black screen
     *  flash on resume from task manager" symptom (which is NOT a renderer
     *  death — onRenderProcessGone never fires, the WebView is alive). */
    ImageView resumeSnapshot;
    Bitmap resumeSnapshotBitmap;
    final Handler snapshotHandler = new Handler();
    /**
     * True once the initial page load has completed and the loading overlay
     * has started fading out. After this point, SPA navigations (which fire
     * onPageStarted/onPageFinished for in-page route changes like ChatGPT's
     * settings tabs) are ignored — they must NOT re-show the loading overlay.
     */
    boolean initialLoadComplete = false;
    private MainWebViewController mainWebViewController;
    private PopupAuthController popupAuthController;
    private TransferController transferController;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private File pendingCameraFile;

    // Pending share-from-outside: file to inject into the MyChatGPT composer.
    // Volatile: written on the background copy thread, read on the UI thread.
    // Cleared in onStop so a forgotten share can never hijack a later
    // file-picker invocation.
    private volatile Uri pendingShareFileUri;

    // Pending WebView permission request (camera/mic) while the OS dialog is up
    private PermissionRequest pendingWebPermissionRequest;

    // True while the OS camera-permission dialog is up on behalf of the file chooser
    private boolean cameraPermForChooser;

    // True when a shared file is waiting for the page to finish loading so the
    // auto-attach sequence can start
    private volatile boolean pendingAutoAttach;

    // True when shared TEXT is waiting for the page to finish loading so the
    // composer focus + keyboard sequence can run
    private volatile boolean pendingAutoFocusText;

    // Drop-injection pipeline: shared file held as base64 until it is fed to
    // the composer via HTML5 drop events (no + menu, no file chooser race).
    private volatile String pendingFileB64;
    private volatile String pendingFileName;
    private volatile String pendingFileMime;

    // Guard so the offline dialog is not shown twice for one failure
    private boolean offlineDialogShowing;

    // Time of the last file injection into the page; suppresses the silent
    // pendingShareFileUri handover for a few seconds afterwards (the site
    // re-opens its file input after a programmatic attach — handing the file
    // over AGAIN then double-attaches it and trips ChatGPT's attach limit).
    private volatile long lastFileInjectionAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ─── Android 12+ splash screen (v6.27) ──────────────────────────
        // MainActivity launches with Theme.AppSplash (see the manifest).
        // The call below swaps the real AppTheme back in before any view
        // work happens. On Android < 12 that is the ONLY effect: the launch
        // window's background/bars are pinned to the same tokens AppTheme
        // uses, so older devices go straight into the loading screen with
        // no flash. On Android 12+ the system additionally shows its splash
        // screen first — a plain frame of the loading-screen background
        // color (day/night aware; the splash icon is a transparent drawable,
        // i.e. deliberately nothing) — and the exit listener below takes
        // over the hand-off: WITHOUT a listener some OEMs play their default
        // exit animation, which on several test devices read as "the icon
        // expands until it fills the screen, then the app fades in". With
        // the listener the splash view crossfades over 300 ms into the
        // app's loading screen, which is painted the exact same background
        // color — so the only visible change is the loading logo (and later
        // the spinner) fading in. No icon zoom, no color flashing, same on
        // every device and OEM.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener(
                    new SplashScreen.OnExitAnimationListener() {
                @Override
                public void onSplashScreenExit(SplashScreenViewProvider provider) {
                    ObjectAnimator fade = ObjectAnimator.ofFloat(
                            provider.getView(), View.ALPHA, 1f, 0f);
                    fade.setDuration(300L);
                    fade.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // The system expects the app to remove the view
                            // itself once its custom exit animation ends.
                            provider.remove();
                        }
                    });
                    fade.start();
                }
            });
        }

        // ─── Foreign-task relay (text-share task bug, rounds 18-19) ────────
        // On some devices/OEMs a TEXT share launches the target INSIDE the
        // sender's task (so back returns to the sender, recents keeps the
        // SENDER's identity, and the real MyChatGPT task is ignored — a fresh
        // instance every time). Detection: we are not the root of our task.
        //
        // Round 18 tried to fix this by re-launching ourselves with NEW_TASK
        // directly from onCreate — but at that point this instance has NO
        // window and is not resumed, so OEM task managers created the MyChatGPT
        // task WITHOUT ever bringing it to the front (round 19 symptom: text
        // copied + "paste it" toast, but MyChatGPT never opened).
        //
        // Corrected approach: hand the share to a momentary transparent
        // trampoline (ShareRelayActivity) started in THIS task — a plain
        // same-task start the system always honors — and finish. Once the
        // trampoline is genuinely resumed (our process foreground, window
        // attached), IT forwards the share to MainActivity with NEW_TASK —
        // a foreground start, which is guaranteed to bring MyChatGPT's own task
        // to the front on every Android version and OEM.
        //
        // Files are deliberately excluded: their share intents can carry URI
        // grants that a re-launch would drop.
        if (isFinishing()) return;
        Intent bootIntent = getIntent();
        if (bootIntent != null
                && !bootIntent.getBooleanExtra(
                        ShareRelayActivity.EXTRA_RELAUNCHED, false)
                && !isTaskRoot()
                && bootIntent.getParcelableExtra(Intent.EXTRA_STREAM) == null) {
            try {
                Intent relay = new Intent(this, ShareRelayActivity.class);
                relay.putExtra(ShareRelayActivity.EXTRA_SHARE_INTENT, bootIntent);
                startActivity(relay);  // same task: no flags, no cross-task start
                finish();
                return;
            } catch (Throwable t) {
                // Never expected. Fall through and boot normally in this
                // task (round-17 behavior: visible, functional, wrong task)
                // rather than leaving the user with nothing at all.
                Log.e(TAG, "share relay start failed; booting in place", t);
            }
        }

        // ─── WebView switcher pre-launch checks ────────────────────────────
        // (1) If the current WebView is too old to support DOCUMENT_START_SCRIPT,
        //     force-open the WebView Manager picker non-cancelable. The user
        //     must pick or install a newer one before they can use the app.
        // (2) If the previous N launches all crashed, assume the chosen WebView
        //     is broken on this device — bounce the user to Settings to pick
        //     a different one.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!WebViewUtil.isSupported()) {
                final WebViewManagerDialog[] dlg = new WebViewManagerDialog[1];
                dlg[0] = new WebViewManagerDialog(this,
                        new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                if (dlg[0] != null && dlg[0].changedWebView()) {
                                    // Force restart to load the new WebView
                                    Intent i = getPackageManager()
                                            .getLaunchIntentForPackage(getPackageName());
                                    if (i != null) {
                                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(i);
                                    }
                                    finishAffinity();
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                } else {
                                    finish();
                                }
                            }
                        });
                dlg[0].setCancelable(false);
                dlg[0].show();
                return;
            }

            if (CrashTracker.hasCrashes()) {
                Log.w(TAG, "Crash threshold reached; bouncing to WebView Manager");
                Toast.makeText(this, R.string.webview_pick_another, Toast.LENGTH_LONG).show();
                CrashTracker.reset();
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
        }
        // ─── End WebView switcher pre-launch checks ────────────────────────

        setContentView(R.layout.activity_main);

        webview = findViewById(R.id.activity_main_webview);
        rootLayout = (ViewGroup) webview.getParent();
        mainWebViewController = new MainWebViewController(
                this,
                rootLayout,
                ChatGptSiteContract.newRequestHeaders(),
                ChatGptSiteContract.MOBILE_USER_AGENT,
                ChatGptSiteContract::isAllowedHost);
        transferController = new TransferController(this);
        popupAuthController = new PopupAuthController(
                this,
                rootLayout,
                mainWebViewController,
                new PopupAuthController.Host() {
                    @Override
                    public WebView getMainWebView() {
                        return webview;
                    }

                    @Override
                    public int getWebViewBackgroundColor() {
                        return isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF;
                    }

                    @Override
                    public Object createJavascriptBridge(WebView popup) {
                        return new WebAppInterface(MainActivity.this, popup);
                    }

                    @Override
                    public void injectPageOverrides(WebView popup) {
                        injectAllOverrides(popup);
                    }

                    @Override
                    public void handleWebPermissionRequest(PermissionRequest request) {
                        MainActivity.this.handleWebPermissionRequest(request);
                    }

                    @Override
                    public boolean openFileChooser(ValueCallback<Uri[]> callback) {
                        return MainActivity.this.openFileChooser(callback);
                    }
                });
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingLogo = findViewById(R.id.loading_logo);
        loadingProgressBar = findViewById(R.id.loading_progress_bar);
        // Bar visibility follows the Settings toggle (off by default); it
        // is re-synced every time the loading screen appears.
        syncLoadingProgressBar();

        // PRIMARY FIX (Bug 2): pin the window + rootLayout background to the
        // same dark-grey / white the WebView itself uses. Without this, the
        // ~1-frame GPU-surface-teardown gap on resume from task manager shows
        // the activity theme's pure-black colorBackground bleeding through a
        // transparent rootLayout — exactly the "brief black flash" symptom.
        // The resumeSnapshot PixelCopy overlay is a stronger mask when it
        // succeeds, but it's a race (vis=8 / GONE if PixelCopy's async
        // callback hasn't fired by onWindowFocusChanged(true)); this
        // background-color fallback is the reliable primary defense.
        applyBackgroundColors();
        // Status/navigation bars from the same theme tokens (re-applied on
        // live dark/light switches in onConfigurationChanged — see the
        // method comment; the theme covers the initial state, this makes
        // one code path for both).
        applySystemBarColors();

        // v6.24.31 diagnostic: log the panel's refresh-rate situation so we
        // can tell whether this OEM throttles third-party apps to 60Hz
        // while Chrome runs at 90/120Hz (a common Samsung/Xiaomi behavior,
        // and a classic cause of "the site feels smoother in the browser").
        // Logcat-only — zero runtime cost.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Display disp = getWindowManager().getDefaultDisplay();
                Display.Mode cur = disp.getMode();
                StringBuilder modes = new StringBuilder();
                for (Display.Mode m : disp.getSupportedModes()) {
                    modes.append(String.format(Locale.US, "%dx%d@%.0f ",
                            m.getPhysicalWidth(), m.getPhysicalHeight(),
                            m.getRefreshRate()));
                }
                Log.d(TAG, String.format(Locale.US,
                        "display: current mode %dx%d@%.0fHz, supported: %s",
                        cur.getPhysicalWidth(), cur.getPhysicalHeight(),
                        cur.getRefreshRate(), modes.toString().trim()));
            } catch (Throwable t) {
                Log.e(TAG, "display mode query failed", t);
            }
        }

        // Resume-snapshot overlay: an ImageView placed at the topmost
        // position of the DECOR VIEW — i.e. covering the FULL WINDOW,
        // including the status-bar strip. This MUST match the geometry of
        // what PixelCopy.request(getWindow(), ...) captures (the whole
        // window). The v6.24.28/29 version added this ImageView to
        // rootLayout instead, which is only the content area BELOW the
        // status bar — so the full-window bitmap was scaled down ~4% to fit,
        // drawing a second black status-bar strip under the real one for the
        // 300ms the overlay was visible (the "app height is bugged for a
        // fraction of a second on resume from task manager" report).
        // Normally GONE; briefly VISIBLE during activity-resume to mask the
        // GPU-surface-recreation flash with a pixel-perfect copy of the
        // previous frame.
        resumeSnapshot = new ImageView(this);
        resumeSnapshot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        resumeSnapshot.setVisibility(View.GONE);
        try {
            ((ViewGroup) getWindow().getDecorView()).addView(resumeSnapshot);
        } catch (Throwable t) {
            // Should never happen (DecorView is a FrameLayout), but never
            // let an overlay problem take the app down — disable the mask.
            Log.e(TAG, "snapshot overlay attach failed", t);
            resumeSnapshot = null;
        }

        // Set correct logo color based on theme (black for light mode, white for dark mode)
        boolean isDark = isDarkMode();
        loadingLogo.setImageResource(isDark ? R.drawable.logo_white : R.drawable.logo_black);

        // Start the loading animation (v6.28: property animators on a
        // hardware layer — see startLoadingLogoAnimation for why the old
        // R.anim.spin_fade view animation was replaced)
        startLoadingLogoAnimation();

        // Wire the WebView up (initial setup; recreated in place if the
        // renderer ever dies — see onRenderProcessGone).
        setupMainWebView(webview);

        // Best-effort sweep of stale one-shot files (camera captures, shared
        // copies) so the cache directory cannot grow without bound.
        sweepCacheDir();

        // First-launch welcome dialog — tells the user about the hidden
        // Settings menu (where the WebView switcher lives). Shows only once
        // per install; dismissed with "Understood".
        WelcomeDialog.showIfNeeded(this);

        // ─── State restoration (activity destroyed / process restarted) ───
        // DuckAssist 0.4.2 pattern: when the system hands back a saved
        // state (activity recreated after a config change we could not
        // intercept, or process killed in the background), restore the
        // WebView's back/forward list and resume the EXACT page — the open
        // conversation included — instead of cold-booting the homepage
        // (the "app relaunches and loses my chat" symptom).
        boolean restoredFromState =
                mainWebViewController.restoreNavigationState(webview, savedInstanceState);
        if (!restoredFromState) {
            mainWebViewController.loadUrl(webview, ChatGptSiteContract.MAIN_URL);
        }

        // Process share-from-outside intent AFTER the initial load has been
        // triggered, so shared text no longer causes a second duplicate
        // navigation (and no longer resets an open conversation when the
        // activity is already running via onNewIntent).
        // SKIPPED when restored from state: the launch intent is stale (it
        // is the intent that originally created this task — re-processing
        // it after a rotation/process restart would re-copy an old share
        // to the clipboard every time).
        if (!restoredFromState) {
            Intent launchIntent = getIntent();
            if (launchIntent != null) {
                handleShareIntent(launchIntent);
            }
        }
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app invalidates a pending shared file so it can never
        // silently hijack a later file-picker invocation.
        pendingShareFileUri = null;
    }

    /**
     * Handle an incoming ACTION_SEND or ACTION_PROCESS_TEXT intent.
     * Saves the text/file for later injection after the WebView loads.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "handleShareIntent: action=" + action + ", type=" + intent.getType());
        markShareActive();

        String sharedText = null;
        Uri sharedFileUri = null;
        String sharedFileMime = null;

        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type != null && type.startsWith("text/") && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    sharedFileUri = fileUri;
                    sharedFileMime = type != null ? type : "*/*";
                }
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                sharedText = text.toString();
            }
        }

        if (sharedText != null) {
            handleSharedText(sharedText);
        } else if (sharedFileUri != null) {
            handleSharedFile(sharedFileUri, sharedFileMime);
        }
    }

    /**
     * Handle shared text — copy to clipboard; the user pastes it into the
     * prompt box. The page is either already loading (cold share-in) or
     * already open (onNewIntent), so no reload is needed.
     */
    private void handleSharedText(String text) {
        // Do not log shared content in release builds (privacy).
        if (BuildConfig.EXPERIMENTAL) {
            Log.i(TAG, "handleSharedText: "
                    + (text.length() > 80 ? text.substring(0, 80) + "..." : text));
        }
        copyToClipboard(text);
        // Focus the composer AND open the soft keyboard so the user can paste
        // immediately — but only once the SPA has REALLY rendered its composer
        // (onPageFinished fires too early: the page steals focus back while
        // finishing its render, closing the keyboard).
        if (webview != null && !isFinishing()) {
            if (initialLoadComplete) {
                waitForComposerReady(20000, this::settleComposerAfterAutoAttach);
            } else {
                pendingAutoFocusText = true;
            }
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("MyChatGPT", text);
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "Text copied to clipboard");
                // Android 13+ already shows its own "Copied" overlay;
                // avoid doubling it up.
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this, "Text copied — paste it into MyChatGPT", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "copyToClipboard failed", e);
        }
    }

    /**
     * Handle shared file — copy the file to the app's cache dir, then try to
     * attach it AUTOMATICALLY: the injected sequence clicks the site's own
     * "+" button and its "Files" menu item, which makes the site call the
     * file chooser — and onShowFileChooser hands over this file with zero
     * user interaction. (Android forbids pushing a file into the page any
     * other way; the page must ask first.)
     */
    private void handleSharedFile(Uri fileUri, String mime) {
        Log.i(TAG, "handleSharedFile: " + fileUri + " (" + mime + ")");

        new Thread(() -> {
            try {
                String fileName = "shared_file_" + System.currentTimeMillis();
                String originalName = getFileNameFromUri(fileUri);
                if (originalName != null && !originalName.isEmpty()) {
                    // The display name comes from the sharing app's provider
                    // and must never be trusted as a path: sanitize it.
                    fileName = TransferController.sanitizeSharedFileName(originalName);
                } else {
                    String ext = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mime);
                    if (ext != null && !ext.isEmpty()) {
                        fileName += "." + ext;
                    }
                }

                File outFile = new File(getCacheDir(), fileName);
                InputStream in = getContentResolver().openInputStream(fileUri);
                if (in == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Cannot read the shared file", Toast.LENGTH_LONG).show());
                    return;
                }
                java.io.OutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();

                final Uri sharedUri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);

                // Read the file back as base64 for the drop-injection path
                // (skipped for huge files — those fall back to manual attach).
                String b64 = null;
                if (outFile.length() < 50L * 1024 * 1024) {
                    byte[] data = java.nio.file.Files.readAllBytes(outFile.toPath());
                    b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                }

                final String fileB64 = b64;
                final String fileFinalName = fileName;
                final String fileMime = (mime != null && mime.contains("/")) ? mime
                        : android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(android.webkit.MimeTypeMap
                                        .getFileExtensionFromUrl("file://x/" + fileName));

                runOnUiThread(() -> {
                    pendingShareFileUri = sharedUri;  // manual +->Files fallback
                    if (fileB64 != null) {
                        pendingFileB64 = fileB64;
                        pendingFileName = fileFinalName;
                        pendingFileMime = fileMime != null ? fileMime : "application/octet-stream";
                        // Auto-attach WILL run (now or once the page loads) —
                        // tell the user to wait instead of tapping + manually,
                        // which would cancel the automatic injection. Text
                        // shares have their own toast in copyToClipboard.
                        Toast.makeText(MainActivity.this,
                                "Please wait — the file will attach automatically",
                                Toast.LENGTH_LONG).show();
                    }
                    if (initialLoadComplete && webview != null) {
                        if (fileB64 != null) {
                            // Page already loaded — wait for it to go quiet.
                            waitForComposerReady(ChatGptSiteContract.COMPOSER_READY_MAX_WAIT_MS, this::runFileDropSequence);
                        } else {
                            // File too large to inject — manual path only.
                        }
                    } else {
                        pendingAutoAttach = true;
                    }
                    final WebView wv = webview;
                    if (wv != null) {
                        wv.postDelayed(() -> {
                            if (pendingShareFileUri != null && !isFinishing()) {
                                Toast.makeText(MainActivity.this,
                                        "Couldn't attach automatically — tap + and choose Files",
                                        Toast.LENGTH_LONG).show();
                            }
                        }, 25000);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "handleSharedFile failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Failed to process file: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = cursor.getString(idx);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    /** Wire up the main WebView (the initial instance from the layout, or a
     *  fresh one after a renderer crash). */
    private void setupMainWebView(WebView mainWebView) {
        int backgroundColor = isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF;
        mainWebViewController.configureMainWebView(
                mainWebView,
                backgroundColor,
                new WebAppInterface(this, mainWebView),
                "AndroidBridge");
        setupClients(mainWebView);
        transferController.setupDownloads(mainWebView);
        setupImageContextMenu(mainWebView);
        installDocumentStartOverrides(mainWebView);
    }

    /**
     * Inject the page overrides at DOCUMENT START in EVERY frame (main frame
     * AND iframes). This is the critical piece for the site's export/share
     * features: those run inside iframes, and evaluateJavascript() at
     * onPageFinished only ever touches the main frame — which is why blob
     * downloads and the share button kept failing while the page itself
     * worked. addDocumentStartJavaScript runs before any site script, in
     * every frame, on every navigation.
     */
    private void installDocumentStartOverrides(WebView webView) {
        try {
            if (WebViewUtil.isSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.PAGE_OVERRIDES_JS, java.util.Collections.singleton("*"));
                // Registered AFTER ChatGptSiteContract.PAGE_OVERRIDES_JS on purpose: the ready
                // watcher's settle fallback reads window.__webgptLoad, which
                // the overrides script installs — document-start scripts run
                // in registration order.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.PAGE_READY_WATCHER_JS, java.util.Collections.singleton("*"));
                // Focus guard: see ChatGptSiteContract.FOCUS_GUARD_JS above. Registered last so
                // any page-script .focus() attempts can be intercepted from
                // the very first script execution.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, ChatGptSiteContract.FOCUS_GUARD_JS, java.util.Collections.singleton("*"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "addDocumentStartJavaScript failed", t);
        }
    }

    /**
     * Solid WebView background matching the current theme (pattern from the
     * AI Studio webclient, which pins #121212 for the same reason). Only the
     * pre-paint flash depends on this — the page itself always covers it
     * once rendered, so pure cosmetic continuity during navigations.
     */
    private void applyWebViewBackground(WebView w) {
        w.setBackgroundColor(isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF);
    }

    /**
     * Apply the same dark-grey / white background to the {@code Window} AND
     * to {@code rootLayout} so the brief GPU-surface-teardown flash on
     * resume from task manager shows the matching color instead of the
     * pure-black window background bleeding through a transparent
     * rootLayout. Without this, the activity theme's
     * {@code ?android:attr/colorBackground} (#000000 in dark mode) is what
     * the user sees during the ~1-frame gap between the window being
     * re-attached and the WebView repainting — which is exactly the
     * "brief black flash" Bug 2 report. The {@link #resumeSnapshot}
     * PixelCopy overlay is a stronger mask when it succeeds, but it is a
     * race (the snapshot is GONE if PixelCopy's async callback hasn't
     * fired by the time {@code onWindowFocusChanged(true)} runs — see
     * toast "snapshot: no overlay to fade (vis=8)"). This background-color
     * fallback is the <em>reliable</em> primary defense; the snapshot
     * overlay remains a nice-to-have on top.
     */
    private void applyBackgroundColors() {
        int bg = isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF;
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        } catch (Throwable t) {
            Log.e(TAG, "window setBackgroundDrawable threw", t);
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(bg);
        }
    }

    /**
     * Re-applies the status / navigation bar colors and the light/dark icon
     * appearance from the CURRENT configuration. AppTheme sets all of these
     * once at window creation; because MainActivity declares uiMode in
     * configChanges (to keep the WebView and its conversation alive across
     * system dark-mode toggles), the activity is NOT recreated when the mode
     * flips — so the themed values go stale: the website adapts by itself
     * (prefers-color-scheme in the WebView) but the bars stayed in the old
     * mode. Called from onCreate and from every onConfigurationChanged; it
     * reads the SAME day/night resources the theme uses
     * (status_bar_bg / window_light_status_bar), so it is an idempotent no-op
     * when the mode did not change. Forks that keep the full configChanges
     * list need this call in their onConfigurationChanged too.
     */
    @SuppressWarnings("deprecation")
    private void applySystemBarColors() {
        try {
            int barColor;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                barColor = getResources().getColor(R.color.status_bar_bg, getTheme());
            } else {
                // 1-arg getColor is the only option below M.
                barColor = getResources().getColor(R.color.status_bar_bg);
            }
            boolean lightBars = getResources().getBoolean(R.bool.window_light_status_bar);
            getWindow().setStatusBarColor(barColor);
            getWindow().setNavigationBarColor(barColor);
            View decor = getWindow().getDecorView();
            int vis = decor.getSystemUiVisibility();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (lightBars) vis |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                else vis &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (lightBars) vis |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                else vis &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            decor.setSystemUiVisibility(vis);
        } catch (Throwable t) {
            Log.e(TAG, "applySystemBarColors failed", t);
        }
    }

    /**
     * JS interface exposed to the wrapped site's JavaScript.
     */
    public static class WebAppInterface {
        private final MainActivity activity;
        private final WebView hostWebView;

        WebAppInterface(MainActivity activity, WebView hostWebView) {
            this.activity = activity;
            this.hostWebView = hostWebView;
        }

        /**
         * Origin gate: the bridge only serves pages hosted on our allowlisted
         * domains. POPUP WebViews (share menus, blob:/about:blank windows)
         * inherit trust from the main WebView — in this app popups are only
         * ever spawned by an allowlisted page (onCreateWindow), so a popup
         * with a blank/blob URL is still "ours". Without this, the site's
         * share menu (opened in a popup) was silently rejected right after
         * the "share called" toast.
         */
        private boolean hostAllowed() {
            try {
                if (urlAllowed(hostWebView.getUrl())) return true;
                WebView main = activity.webview;
                if (main != null && main != hostWebView && urlAllowed(main.getUrl())) {
                    return true;  // popup spawned by an allowlisted page
                }
                debugLog("bridge blocked: " + hostWebView.getUrl());
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        private static boolean urlAllowed(String url) {
            return ChatGptSiteContract.isAllowedWebUrl(url);
        }

        /**
         * Called by the ChatGptSiteContract.PAGE_READY_WATCHER_JS poller when the DOM signals
         * that the SPA is REALLY rendered (composer + late-appearing splash
         * markers, a restored conversation, or the settle heuristic).
         * Drives the loading overlay off the screen the instant the page is
         * usable instead of waiting for onPageFinished + a blind delay.
         */
        @JavascriptInterface
        public void pageReady() {
            if (!hostAllowed()) return;  // origin gate
            activity.runOnUiThread(() -> activity.onDomReady());
        }

        /**
         * Called by the navigator.share({ text }) JS override.
         * Copies to the system clipboard SILENTLY (no toast) —
         * The site shows its own "Copied!" feedback in the UI.
         */
        @JavascriptInterface
        public void copyToClipboard(final String text) {
            if (!hostAllowed()) return;  // origin gate
            activity.runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip =
                                android.content.ClipData.newPlainText("MyChatGPT", text);
                        clipboard.setPrimaryClip(clip);
                        Log.i("MyChatGPTApp", "Text copied to clipboard via JS override");
                    }
                } catch (Exception e) {
                    Log.e("MyChatGPTApp", "copyToClipboard (JS) failed", e);
                }
            });
        }

        /**
         * navigator.share({ text, url }) — open the real system share sheet.
         * UNGATED by design: the system share sheet itself is the consent UI
         * (the user reviews the content before anything leaves the device),
         * so an origin gate adds no security here — and it broke sharing
         * from the site's share popup, whose own URL is blob:/about:blank.
         */
        @JavascriptInterface
        public void shareText(final String text, final String url) {
            debugLog("shareText invoked");
            activity.runOnUiThread(() -> activity.transferController.shareTextNative(text, url));
        }

        /**
         * navigator.share({ files: [File] }) — decode the data URL and hand
         * the file to the system share sheet via FileProvider.
         */
        /** navigator.share({ files }) — UNGATED (system sheet = consent UI). */
        @JavascriptInterface
        public void shareFile(final String title, final String dataUrl,
                              final String fileName, final String mime) {
            debugLog("shareFile invoked: " + fileName);
            activity.runOnUiThread(() -> activity.transferController.shareFileNative(title, dataUrl, fileName, mime));
        }

        /**
         * Blob-download callback: the injected fetch/FileReader snippet hands
         * the payload back as a data-URL string. Replaces the old
         * window.__blobResult polling, which capped downloads at 2 seconds.
         */
        @JavascriptInterface
        public void onBlobResult(final String dataUrl) {
            if (!hostAllowed()) return;
            activity.runOnUiThread(() -> activity.transferController.handleBlobResult(dataUrl));
        }

        /** Blob-download callback: the in-page fetch or read failed. */
        @JavascriptInterface
        public void onBlobFailed() {
            activity.runOnUiThread(() -> activity.transferController.handleBlobFailed());
        }

        /**
         * Direct blob-download path: fired by the anchor-click hook in
         * injectAllOverrides() while the blob URL is still alive. Carries the
         * suggested filename plus the full data-URL payload.
         */
        @JavascriptInterface
        public void onBlobDownload(final String name, final String dataUrl) {
            if (!hostAllowed()) return;
            activity.runOnUiThread(() -> activity.transferController.handleBlobDownload(name, dataUrl));
        }

        /**
         * Chunked blob transfer: large exports (PDF etc.) are delivered in
         * 256KB base64 pieces to stay clear of any JS-to-Java bridge string
         * limits. @JavascriptInterface calls are synchronous from JS, so the
         * chunks arrive in order; the final chunk (index == total-1)
         * assembles and saves the file.
         */
        @JavascriptInterface
        public void onBlobChunk(final String name, final String mime, final int index,
                                final int total, final String data) {
            activity.transferController.onBlobChunk(name, mime, index, total, data);
        }

        /**
         * Debug telemetry (debug builds only): a visible toast reporting
         * which page APIs the site actually exercises (share, window.open,
         * blob downloads). Invaluable when diagnosing site features that
         * misbehave inside a WebView — no adb needed.
         */
        @JavascriptInterface
        public void debugLog(final String msg) {
            // Site-side diagnostic channel — logcat only (experimental
            // builds). Never toasts on screen: every Toast.show() is a
            // synchronous binder round-trip on the UI thread (the
            // v6.24.31 navigation-hot-path regression).
            if (BuildConfig.EXPERIMENTAL) {
                Log.d(TAG, "bridge: " + msg);
            }
        }

        /** Drop-injection result (functional, not debug): clears the manual
         *  fallback on success, prompts the user on failure. */
        @JavascriptInterface
        public void onFileDropResult(final boolean ok, final String detail) {
            activity.runOnUiThread(() -> activity.handleFileDropResult(ok, detail));
        }
    }

    private void setupClients(final WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // Do not mirror the site's console (it can contain chat
                // fragments) into logcat on release builds.
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                }
                return true;
            }

            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                // Optional page-progress bar at the top of the loading
                // screen (Settings → Interface, off by default).
                // ⚠ The raw WebView progress LIES on chatgpt.com: it
                // reaches 100 as soon as the HTML shell has loaded,
                // long before the SPA has hydrated — the "misleading
                // full load" that ChatGptSiteContract.PAGE_READY_WATCHER_JS exists to
                // detect (the SAME chatgpt.com-exclusive system the
                // loading screen itself waits on; see WEBSITE_SPECIFICS
                // §2.4). Driving the bar with the raw value made it
                // claim "fully loaded" while the overlay was still up.
                // So the raw percentage is mapped onto the first 90% of
                // the bar, and the last 10% is reserved: it is only ever
                // completed by hideLoadingOverlayNow(), i.e. at the
                // exact moment the real-ready signal fires and the
                // loading screen starts fading. setProgressCompat
                // animates between values per the Material motion spec.
                // The overlay guard keeps SPA navigations (which also
                // fire progress long after the first paint) from
                // touching a bar that is not on screen.
                if (loadingProgressBar != null
                        && loadingOverlay != null
                        && !initialLoadComplete
                        && loadingOverlay.getVisibility() == View.VISIBLE) {
                    int clamped = Math.max(0, Math.min(100, newProgress));
                    loadingProgressBar.setProgressCompat(clamped * 9 / 10, true);
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // Shared implementation (was previously duplicated in the
                // popup client, and the two copies had drifted apart).
                return openFileChooser(callback);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // The window.open JS override handles external links (X, Reddit, LinkedIn)
                // BEFORE they reach onCreateWindow. Internal popups (share menu, OAuth)
                // go through createPopup which creates a proper popup WebView.
                return popupAuthController.createPopup(resultMsg);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Legacy callback (API < 24, fires for all frames): original
                // permissive behavior — null-host URLs must load, or blob:/
                // data: iframe apps (investigation panel) break.
                return mainWebViewController.shouldOverrideFrame(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v,
                                                    android.webkit.WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && !request.isForMainFrame()) {
                    return mainWebViewController.shouldOverrideFrame(request.getUrl().toString());
                }
                return mainWebViewController.shouldOverrideMainFrame(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // Legacy callback (API < 23) fires for the main frame only.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    showOfflineDialog();
                }
            }

            @Override
            public void onReceivedError(WebView view,
                                        android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                // API 23+: react to main-frame failures only, so a failed
                // subresource on the SPA does not trigger the dialog.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && request.isForMainFrame()) {
                    showOfflineDialog();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                // Renderer death (OOM / driver crash): rebuild the WebView in
                // place instead of letting the whole process die.
                boolean wasMain = (view == webview);
                // didCrash()==true → renderer crashed; false → OS killed it
                // for memory pressure (the more common case on resume from
                // task manager).
                boolean didCrash = (detail != null) && detail.didCrash();
                String reasonStr = didCrash ? "CRASH" : "OOM_KILL";
                Log.e(TAG, "WebView renderer gone; recreating WebView (main=" + wasMain + ", reason=" + reasonStr + ")");
                if (wasMain) {
                    initialLoadComplete = false;
                    // Don't immediately show the loading overlay — let
                    // onPageStarted's 200ms-delayed show handle it. If the
                    // page reloads from cache within that window (typical on
                    // resume after Android killed the renderer in the
                    // background), the overlay never shows at all and the
                    // user just sees a brief black WebView instead of the
                    // full loading-screen flash. Fixes the "brief black
                    // screen on resume from task manager" report (present
                    // since the official v6.24 release).
                    webview = mainWebViewController.recreateMainWebView(
                            webview, MainActivity.this::setupMainWebView);
                    mainWebViewController.loadUrl(webview, ChatGptSiteContract.MAIN_URL);
                } else {
                    if (popupAuthController != null) popupAuthController.removePopup(view);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                // Only show the loading overlay during the INITIAL page load.
                // Once initialLoadComplete is true, SPA navigations (ChatGPT's
                // settings tabs, share popups, etc.) fire onPageStarted too —
                // but we ignore them so the overlay doesn't flash.
                if (!initialLoadComplete && loadingOverlay != null
                        && loadingOverlay.getVisibility() != View.VISIBLE) {
                    // Delay showing the overlay by 200ms. If the page loads
                    // from cache within that window (common on resume after
                    // renderer-gone), the overlay never appears and the user
                    // is spared the brief loading-screen flash. If the page
                    // takes longer, the overlay shows as usual.
                    webview.postDelayed(() -> {
                        if (!initialLoadComplete && loadingOverlay != null
                                && loadingOverlay.getVisibility() != View.VISIBLE) {
                            loadingOverlay.setVisibility(View.VISIBLE);
                            if (loadingLogo != null) {
                                boolean dark = isDarkMode();
                                loadingLogo.setImageResource(dark ? R.drawable.logo_white : R.drawable.logo_black);
                                startLoadingLogoAnimation();
                            }
                            syncLoadingProgressBar();
                        }
                    }, 200);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Page loaded successfully → mark this launch as non-crashing.
                CrashTracker.reset();
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                kickPendingSharePipelines();
                // If the initial load is already complete (SPA navigation or
                // the DOM-ready signal already fired), do nothing — no
                // overlay to hide.
                if (initialLoadComplete) return;
                // FALLBACK ONLY. The primary overlay-dismissal signal is the
                // DOM-ready watcher (ChatGptSiteContract.PAGE_READY_WATCHER_JS → onDomReady),
                // which fires the moment the composer + late splash markers
                // are really rendered — usually well before or after this
                // point, never tied to the load event. This timer only
                // guarantees the overlay cannot get stuck if the watcher
                // never ran at all (ancient WebView, bridge failure).
                webview.postDelayed(() -> {
                    if (loadingOverlay == null || initialLoadComplete) return;
                    hideLoadingOverlayNow();
                }, ChatGptSiteContract.PAGE_FINISHED_READY_FALLBACK_MS);
            }

        });
    }

    /**
     * DOM-ready signal from the page (AndroidBridge.pageReady, driven by
     * ChatGptSiteContract.PAGE_READY_WATCHER_JS): the composer plus the last-appearing splash
     * elements are REALLY in the rendered DOM. Dismiss the loading overlay
     * right now — no blind delay, no waiting for the load event.
     * Idempotent: after the first call (or the fallback path) the
     * initialLoadComplete flag makes every later signal a no-op, so SPA
     * re-navigation, OAuth round trips and renderer-crash reloads are all
     * safe. (Renderer crash resets the flag and shows the overlay again —
     * the fresh document re-runs the watcher and re-fires this.)
     */
    void onDomReady() {
        if (isFinishing() || initialLoadComplete) return;
        hideLoadingOverlayNow();
        // A share arrived while the page was still booting: the DOM signal
        // means the composer is interactive NOW — start the pipeline
        // immediately instead of waiting for onPageFinished.
        kickPendingSharePipelines();
    }

    /**
     * Set initialLoadComplete and fade the loading overlay out.
     * The flag is set BEFORE starting the animation: any onPageStarted that
     * fires during the fade window sees initialLoadComplete=true and skips
     * re-showing the overlay (SPA navigation during fade-out used to make
     * the loading screen flash).
     */
    private void hideLoadingOverlayNow() {
        if (loadingOverlay == null || initialLoadComplete) return;
        initialLoadComplete = true;
        // The real-ready signal just fired → complete the optional
        // progress bar (capped at 90% while the page loads — see
        // onProgressChanged) in the same instant the overlay starts
        // fading. "Bar full" and "loading screen gone" are therefore the
        // same event: the bar can never claim the page is ready while
        // the chatgpt.com misleading-full-load detection is still
        // waiting for hydration.
        if (loadingProgressBar != null
                && loadingProgressBar.getVisibility() == View.VISIBLE) {
            loadingProgressBar.setProgressCompat(100, true);
        }
        Animation fadeOut = AnimationUtils.loadAnimation(MainActivity.this, R.anim.fade_out);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                loadingOverlay.setVisibility(View.GONE);
                stopLoadingLogoAnimation();
            }
            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        loadingOverlay.startAnimation(fadeOut);
    }

    /**
     * Loading-logo animation (v6.28). The old R.anim.spin_fade VIEW
     * animation (rotate + alpha applied as per-frame transformations of a
     * software-rendered view) visibly stuttered while the WebView was busy
     * loading the page. Property animators (View.ROTATION / View.ALPHA)
     * plus a hardware layer for the duration of the animation fix that: the
     * logo bitmap is pinned in a GPU texture and the transforms are applied
     * by the render thread, so page-load work on the UI thread can no
     * longer stall the spin. Same visual as before: one revolution per 2 s
     * (linear), alpha 0.3 → 1 → 0.3 per 2 s cycle (the calm "thinking"
     * pulse).
     */
    private void startLoadingLogoAnimation() {
        if (loadingLogo == null) return;
        stopLoadingLogoAnimation();
        // Hardware layer only while animating — releases the texture when
        // the loading screen goes away (see stopLoadingLogoAnimation).
        loadingLogo.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        ObjectAnimator spin = ObjectAnimator.ofFloat(
                loadingLogo, View.ROTATION, 0f, 360f);
        spin.setDuration(2000L);
        spin.setInterpolator(new LinearInterpolator());
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setRepeatMode(ValueAnimator.RESTART);

        ObjectAnimator pulse = ObjectAnimator.ofFloat(
                loadingLogo, View.ALPHA, 0.3f, 1f);
        pulse.setDuration(1000L);
        pulse.setInterpolator(new AccelerateDecelerateInterpolator());
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.setRepeatMode(ValueAnimator.REVERSE);

        loadingLogoAnim = new AnimatorSet();
        loadingLogoAnim.playTogether(spin, pulse);
        loadingLogoAnim.start();
    }

    /**
     * Cancels the loading-logo animation and releases its hardware layer.
     * Called whenever the loading overlay leaves the screen (fade-out end,
     * offline dialog, activity destroy) and before a restart.
     */
    private void stopLoadingLogoAnimation() {
        if (loadingLogoAnim != null) {
            loadingLogoAnim.cancel();
            loadingLogoAnim = null;
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
            loadingLogo.setLayerType(View.LAYER_TYPE_NONE, null);
        }
    }

    /**
     * Re-syncs the optional page-progress bar with the Settings toggle
     * ("Show page loading progress", off by default) and resets it for a
     * fresh load. Called every time the loading overlay (re)appears — cold
     * start, offline retry, renderer-crash reload — so the bar always
     * starts empty and in step with the logo. The pref is read fresh each
     * time, so toggling it takes effect on the next load without a restart.
     */
    private void syncLoadingProgressBar() {
        if (loadingProgressBar == null) return;
        boolean show = false;
        try {
            show = getSharedPreferences("webgpt_prefs", MODE_PRIVATE)
                    .getBoolean("loading_progress", false);
        } catch (Throwable t) {
            Log.e(TAG, "loading-progress pref read failed", t);
        }
        loadingProgressBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) loadingProgressBar.setProgressCompat(0, false);
    }

    /**
     * Launch the deferred share pipelines (a file or text that arrived while
     * the page was still loading). Called from BOTH onPageFinished and
     * onDomReady — whoever comes first wins; the flags are cleared
     * synchronously on the UI thread before the async wait begins, so a
     * second invocation is always a no-op (no double attach).
     */
    private void kickPendingSharePipelines() {
        // A shared file arrived while the page was still loading — wait
        // for the REAL composer, then inject via drop events.
        if (pendingAutoAttach && pendingFileB64 != null) {
            pendingAutoAttach = false;
            waitForComposerReady(ChatGptSiteContract.COMPOSER_READY_MAX_WAIT_MS, MainActivity.this::runFileDropSequence);
        } else if (pendingAutoAttach) {
            pendingAutoAttach = false;  // no injectable payload — manual path
        }
        // Shared TEXT arrived while the page was still loading — wait
        // for the REAL composer, then focus it + open the keyboard.
        if (pendingAutoFocusText) {
            pendingAutoFocusText = false;
            waitForComposerReady(ChatGptSiteContract.COMPOSER_READY_MAX_WAIT_MS, MainActivity.this::settleComposerAfterAutoAttach);
        }
    }

    /**
     * Wait until the SPA has REALLY loaded — using EVENTS, not timers:
     *   - the composer element exists, AND
     *   - the splash is fully rendered (visible greeting wrapper
     *     [data-splash-headline-option] or mobile suggestion chips — the
     *     LAST elements to appear, verified against full DOM snapshots;
     *     presence is not enough on mobile, where the desktop greeting
     *     wrapper sits CSS-hidden in the DOM — hence the visibility test),
     *     OR the settle heuristic:
     *   - no DOM mutation anywhere for 2s (hydration/SPA re-renders mutate
     *     continuously — the "greening text" phase), AND
     *   - no fetch/XHR STARTED for 1.5s (network settle; long-lived streams
     *     that began long ago do not block).
     * The splash shortcut only matches the EMPTY new-chat state (chips and
     * greeting never render inside an existing conversation), so shares
     * into an already-running chat keep the battle-tested quiet heuristic.
     * The tracker hooks (window.__webgptLoad) are installed at document
     * start by ChatGptSiteContract.PAGE_OVERRIDES_JS, so the ages are real activity timestamps,
     * not elapsed-time guesses — fast devices settle fast, slow ones slow.
     * The deadline is only a safety net, never the primary mechanism.
     */
    private void waitForComposerReady(int maxMs, Runnable action) {
        final WebView wv = webview;
        if (wv == null || isFinishing()) return;
        final long deadline = SystemClock.elapsedRealtime() + maxMs;
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (webview == null || isFinishing()) return;
            webview.evaluateJavascript(
                    ChatGptSiteContract.COMPOSER_READY_PROBE_JS,
                    res -> {
                        // evaluateJavascript delivers strings JSON-quoted:
                        // strip the surrounding quotes before parsing.
                        String sig = res == null ? "" : res;
                        if (sig.length() >= 2 && sig.startsWith("\"") && sig.endsWith("\"")) {
                            sig = sig.substring(1, sig.length() - 1);
                        }
                        if (sig.startsWith("ready")) {
                            action.run();
                        } else if (SystemClock.elapsedRealtime() < deadline) {
                            webview.postDelayed(tick[0], ChatGptSiteContract.COMPOSER_READY_POLL_MS);
                        } else {
                            action.run();
                        }
                    });
        };
        tick[0].run();
    }

    /** onPageFinished fallback (and re-injection after SPA navigations). */
    private void injectAllOverrides(WebView v) {
        v.evaluateJavascript(ChatGptSiteContract.PAGE_OVERRIDES_JS, null);
        // Ready watcher fallback for WebViews without DOCUMENT_START_SCRIPT
        // support: starts late (page already rendered) but then usually finds
        // the markers on its very first tick. MAIN WebView only — the watcher
        // must never run in OAuth/share popups.
        if (v == webview) {
            v.evaluateJavascript(ChatGptSiteContract.PAGE_READY_WATCHER_JS, null);
        }
    }

    /**
     * Feed the pending shared file straight into the site's composer via
     * HTML5 drop events: the base64 payload is pushed into the page in
     * chunks, then a File is constructed in JS and dragenter/dragover/drop
     * are dispatched on the composer. No + menu, no file chooser, none of
     * the focus-race fragility — this is the same code path the site uses
     * for real drag-and-drop attachments.
     */
    private void runFileDropSequence() {
        final String b64 = pendingFileB64;
        final String name = pendingFileName;
        final String mime = pendingFileMime;
        if (webview == null || isFinishing() || b64 == null || name == null) return;
        pendingFileB64 = null;  // consumed

        webview.evaluateJavascript(ChatGptSiteContract.FILE_BUFFER_RESET_JS, null);
        final int CH = ChatGptSiteContract.FILE_INJECTION_BASE64_CHUNK_SIZE;  // 512KB base64 chunks
        for (int i = 0; i < b64.length(); i += CH) {
            final String chunk = b64.substring(i, Math.min(i + CH, b64.length()));
            // base64 alphabet is JS-string-safe — no escaping needed
            webview.evaluateJavascript(
                    ChatGptSiteContract.appendFileBufferJs(chunk),
                    null);
        }

        final String safeName = name.replace("\\", "_").replace("'", "\\'");
        final String safeMime = (mime != null ? mime : "application/octet-stream").replace("'", "");
        // Mark the injection moment BEFORE dispatching: any file-chooser
        // opening during/right after the injection is a site re-trigger,
        // not user intent (see the guard in openFileChooser).
        lastFileInjectionAt = SystemClock.elapsedRealtime();
        /* PRIMARY PATH — hidden file input, NO drag events: the composer has
         * an <input type=file> (the + -> Files flow uses it). Setting
         * input.files programmatically and firing input+change runs the
         * site's own attach handler with ZERO drag events — which means the
         * full-screen drop overlay (triggered by synthetic dragenter/dragover
         * in earlier builds, with no reliable teardown) is never shown. */
        String dropJs = ChatGptSiteContract.buildFileDropJs(safeName, safeMime);
        webview.evaluateJavascript(dropJs, null);

        // Re-assert focus + keyboard AFTER the drop lands: the SPA can still
        // run a late hydration pass that steals focus; several staggered
        // re-assertions keep the composer focused (and the attachment
        // committed) through it.
        webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.DROP_REFOCUS_DELAY_1_MS);
        webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.DROP_REFOCUS_DELAY_2_MS);
        webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.DROP_REFOCUS_DELAY_3_MS);
        webview.postDelayed(this::showKeyboardForComposer, ChatGptSiteContract.DROP_KEYBOARD_DELAY_1_MS);
        webview.postDelayed(this::showKeyboardForComposer, ChatGptSiteContract.DROP_KEYBOARD_DELAY_2_MS);
    }

    /** Bridge callback: the page accepted (or rejected) the injected drop. */
    private void handleFileDropResult(boolean ok, String detail) {
        Log.i(TAG, "drop result: " + (ok ? "ok" : "failed") + " " + detail);
        if (ok) {
            // Attached — clear the manual-fallback URI so it can never hijack
            // a later file-chooser invocation.
            pendingShareFileUri = null;
            // Verify 3s later that the attachment is STILL visible in the
            // composer (catches the site discarding it — e.g. an attach-limit
            // rejection — right after accepting it).
            final String checkName = pendingFileName;
            webview.postDelayed(() -> verifyAttachmentVisible(checkName), ChatGptSiteContract.ATTACHMENT_VERIFY_DELAY_MS);
        } else if (pendingShareFileUri != null) {
            Toast.makeText(this,
                    "Couldn't attach automatically — tap + and choose Files",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Diagnostic: does the composer still show an attachment chip? */
    private void verifyAttachmentVisible(String name) {
        WebView wv = webview;
        if (wv == null || isFinishing() || name == null) return;
        final String jsName = name.replace("\\", "_").replace("'", "\\'");
        String js = ChatGptSiteContract.buildAttachmentVisibilityJs(jsName);
        wv.evaluateJavascript(js, res -> {
            String r = res == null ? "" : res;
            if (r.length() >= 2 && r.startsWith("\"") && r.endsWith("\"")) {
                r = r.substring(1, r.length() - 1);
            }
            Log.i(TAG, "attach check: " + r);
        });
    }

    /**
     * Long-press context menu for images.
     * Shows a Material AlertDialog (same style as the WebView Manager) with
     * "Share image" and "Download image" options.
     */
    private void setupImageContextMenu(WebView webView) {
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null && result.getType() == WebView.HitTestResult.IMAGE_TYPE
                    && result.getExtra() != null) {
                showImageDialog(result.getExtra());
                return true;
            }
            return false;
        });
    }

    /**
     * Show a Material AlertDialog (same style as the WebView Manager) with
     * image action options. Uses a custom layout with icon + title + subtitle
     * rows for a richer Material 3 appearance.
     */
    private void showImageDialog(final String imageUrl) {
        View view = getLayoutInflater().inflate(R.layout.dialog_image_actions, null);
        View shareBtn = view.findViewById(R.id.action_share);
        View downloadBtn = view.findViewById(R.id.action_download);

        final com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.image_actions_title)
                        .setView(view);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();

        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Loading image to share...", Toast.LENGTH_SHORT).show();
                transferController.downloadAndShareImageFile(imageUrl);
            });
        }
        if (downloadBtn != null) {
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Downloading image...", Toast.LENGTH_SHORT).show();
                transferController.downloadImageToDownloads(imageUrl);
            });
        }
        dialog.show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback == null) {
                return;
            }
            if (resultCode != RESULT_OK) {
                if (pendingCameraFile != null && pendingCameraFile.exists()) {
                    pendingCameraFile.delete();
                }
                pendingCameraUri = null;
                pendingCameraFile = null;
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return;
            }

            Uri[] results = null;
            if (data == null || (data.getData() == null && data.getClipData() == null)) {
                if (pendingCameraFile != null && pendingCameraFile.exists() && pendingCameraFile.length() > 0) {
                    results = new Uri[]{pendingCameraUri};
                    Log.i(TAG, "Camera capture result: " + pendingCameraUri);
                }
            } else {
                android.content.ClipData clipData = data.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            pendingCameraUri = null;
            pendingCameraFile = null;
        }
    }

    // ─── Permission / navigation / chooser helpers ─────────────────────

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Handles a WebView permission request (camera/mic):
     *  - only for allowlisted origins,
     *  - requests the missing OS permission at runtime,
     *  - grants only what the user actually approved.
     */
    private void handleWebPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                Uri origin = request.getOrigin();
                if (origin == null || !ChatGptSiteContract.isAllowedHost(origin.getHost())) {
                    try {
                        request.deny();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                List<String> toRequest = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.CAMERA)) {
                        toRequest.add(android.Manifest.permission.CAMERA);
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                        toRequest.add(android.Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (toRequest.isEmpty()) {
                    grantWebPermissionRequest(request);
                } else {
                    if (pendingWebPermissionRequest != null) {
                        try {
                            pendingWebPermissionRequest.deny();
                        } catch (Throwable ignored) {
                        }
                    }
                    pendingWebPermissionRequest = request;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            toRequest.toArray(new String[0]), REQUEST_MEDIA_PERM);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onPermissionRequest failed", t);
            }
        });
    }

    private void grantWebPermissionRequest(PermissionRequest request) {
        List<String> granted = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.CAMERA)) {
                granted.add(r);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                granted.add(r);
            }
        }
        try {
            if (granted.isEmpty()) {
                request.deny();
            } else {
                request.grant(granted.toArray(new String[0]));
            }
        } catch (Throwable t) {
            Log.e(TAG, "grantWebPermissionRequest failed", t);
        }
    }

    /** Shared file-chooser implementation (previously duplicated in both clients). */
    private boolean openFileChooser(ValueCallback<Uri[]> callback) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        filePathCallback = callback;

        // If we have a pending shared file, return it immediately — UNLESS
        // we injected a file moments ago: the site re-opens its file input
        // right after a programmatic attach, and handing the same file over
        // again double-attaches it (which trips ChatGPT's attach limit and
        // makes the attachment disappear).
        if (pendingShareFileUri != null
                && SystemClock.elapsedRealtime() - lastFileInjectionAt < ChatGptSiteContract.FILE_CHOOSER_RETRIGGER_GUARD_MS) {
            Log.i(TAG, "ignoring site re-trigger after injection");
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
            return true;
        }
        if (pendingShareFileUri != null) {
            Log.i(TAG, "onShowFileChooser: returning pending shared file " + pendingShareFileUri);
            filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
            filePathCallback = null;
            pendingShareFileUri = null;
            Log.i(TAG, "auto-attach: file handed to page");
            // The site only COMMITS a pending attachment while the composer
            // is focused shortly after the file lands — unattended, it
            // silently drops the file about a second later (users had to tap
            // the text box within that window). Focus the composer
            // programmatically, several times, inside that window.
            webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.AUTO_ATTACH_FOCUS_DELAY_1_MS);
            webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.AUTO_ATTACH_FOCUS_DELAY_2_MS);
            webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.AUTO_ATTACH_FOCUS_DELAY_3_MS);
            webview.postDelayed(this::settleComposerAfterAutoAttach, ChatGptSiteContract.AUTO_ATTACH_FOCUS_DELAY_4_MS);
            return true;
        }

        // Because the app declares CAMERA in the manifest, Android requires
        // the runtime permission to be HELD before a capture intent is
        // launched — otherwise the camera app fails silently. Ask first,
        // then show the chooser from the permission result.
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            cameraPermForChooser = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);
            return true;
        }
        launchFileChooserNow(true);
        return true;
    }

    /** Launch the system file chooser; include the camera option when usable. */
    private void launchFileChooserNow(boolean includeCamera) {
        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.setType("*/*");
        contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        boolean cameraReady = false;
        if (includeCamera) {
            try {
                File cameraFile = new File(getCacheDir(),
                        "camera_capture_" + System.currentTimeMillis() + ".jpg");
                Uri cameraUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", cameraFile);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                // Some camera apps re-read the capture for EXIF rotation or
                // thumbnails and crash on a missing read grant — grant both.
                cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                pendingCameraUri = cameraUri;
                pendingCameraFile = cameraFile;
                cameraReady = true;
            } catch (Exception e) {
                Log.e(TAG, "Camera setup failed", e);
                pendingCameraUri = null;
                pendingCameraFile = null;
            }
        }

        Intent chooser = Intent.createChooser(contentIntent, "Select file");
        if (cameraReady) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        }
        try {
            startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
        } catch (Exception e) {
            Log.e(TAG, "File chooser failed", e);
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
        }
    }

    /**
     * Nudge the composer after the auto-attached file is handed to the page:
     * send Escape (closes the leftover "+" menu) and focus the prompt box.
     * This reproduces the user tap that the site needs to commit the
     * attachment within its ~1s window.
     */
    /**
     * Nudge the composer after the auto-attached file is handed to the page:
     * simulate the user's tap on the prompt box (pointer/mouse sequence +
     * focus). NO Escape key — Escape dismisses the pending attachment, which
     * is exactly how round 5 broke file sharing. The tap is what the site
     * needs to commit the attachment within its ~1s window.
     */
    /**
     * Mark a 6-second window during which the focus guard is bypassed, so
     * the share pipeline's own .focus() calls can land and commit the
     * attachment. Without this, ChatGptSiteContract.FOCUS_GUARD_JS would block the share's own
     * focus() and the attachment would never land.
     *
     * The timestamp is set in THREE places so all share entry points are
     * covered:
     *  - Here, Java-side in handleShareIntent — runs via evaluateJavascript
     *    on the JS thread, which lands before the page's resume-time
     *    visibilitychange handler can fire, so there's no flicker on
     *    share-resume (the share's own focus() wins the race).
     *  - Inside settleComposerAfterAutoAttach's JS string — covers cold
     *    start (no resume event fires on cold start) and any staggered
     *    re-focus attempts the SPA makes later.
     *  - Inside runFileDropSequence's dropJs string — same coverage for
     *    the file-drop pipeline.
     *
     * Auto-expires (6s), so it can't get stuck on. If a share ever
     * misfires the user just waits 6 seconds and the guard re-engages.
     */
    private void markShareActive() {
        WebView wv = webview;
        if (wv == null || isFinishing()) return;
        wv.evaluateJavascript(ChatGptSiteContract.MARK_SHARE_ACTIVE_JS, null);
    }

    /**
     * Focus the composer so a pending attachment commits / pasted text lands.
     * FOCUS ONLY — no pointer/click dispatch: the old selector
     * ([data-testid*=composer]) matched the + ATTACH BUTTON in the current
     * ChatGPT UI, so the settle code was clicking + instead of focusing the
     * text box (opening menus, never activating the composer). The selector
     * list below deliberately matches only editable elements.
     */
    private void settleComposerAfterAutoAttach() {
        WebView wv = webview;
        if (wv == null || isFinishing()) return;
        String js = ChatGptSiteContract.COMPOSER_FOCUS_JS;
        wv.evaluateJavascript(js, null);
        // Programmatic JS focus does not reliably summon the Android IME —
        // the site only commits a pending attachment while the composer is
        // FOCUSED, which on a touch device means the keyboard being up.
        showKeyboardForComposer();
    }

    /** Show the soft keyboard for the WebView's composer. */
    private void showKeyboardForComposer() {
        try {
            WebView wv = webview;
            if (wv == null || isFinishing()) return;
            wv.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Throwable t) {
            Log.e(TAG, "showKeyboardForComposer failed", t);
        }
    }

    /** Offline dialog with retry (main-frame load failures). */
    private void showOfflineDialog() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || offlineDialogShowing) return;
            offlineDialogShowing = true;
            if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("Connection problem")
                    .setMessage("Couldn't load chatgpt.com. Check your internet connection and try again.")
                    .setCancelable(false)
                    .setPositiveButton("Retry", (d, w) -> {
                        offlineDialogShowing = false;
                        initialLoadComplete = false;
                        if (loadingOverlay != null) {
                            loadingOverlay.setVisibility(View.VISIBLE);
                            // The overlay was force-hidden while the dialog
                            // was up: restart the logo animation and re-sync
                            // the optional progress bar for the fresh load.
                            startLoadingLogoAnimation();
                            syncLoadingProgressBar();
                        }
                        mainWebViewController.loadUrl(webview, ChatGptSiteContract.MAIN_URL);
                    })
                    .setNegativeButton("Close app", (d, w) -> {
                        offlineDialogShowing = false;
                        // Actually close the app (whole task), not just the dialog.
                        finishAffinity();
                    })
                    .show();
        });
    }

    /** Delete one-shot cache files older than 48 hours. */
    private void sweepCacheDir() {
        new Thread(() -> {
            try {
                File[] files = getCacheDir().listFiles();
                if (files == null) return;
                long cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000;
                for (File f : files) {
                    String n = f.getName();
                    if ((n.startsWith("camera_capture_") || n.startsWith("shared_image_")
                            || n.startsWith("shared_file_")) && f.lastModified() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (transferController != null
                && transferController.onRequestPermissionsResult(requestCode, grantResults)) {
            return;
        } else if (requestCode == REQUEST_MEDIA_PERM) {
            PermissionRequest req = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (req != null) {
                grantWebPermissionRequest(req);
            }
        } else if (requestCode == REQUEST_CAMERA_PERM) {
            // File chooser deferred until the camera permission was resolved:
            // show it now — with the camera option only if permission was
            // granted (works with "Ask every time" too).
            if (cameraPermForChooser) {
                cameraPermForChooser = false;
                boolean granted = grantResults.length > 0
                        && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (filePathCallback != null) {
                    launchFileChooserNow(granted);
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Reaching PAUSE proves this launch was real and interactive — any
        // process death while backgrounded afterwards is NORMAL lifecycle
        // (OEM task managers kill background processes aggressively,
        // floating/picture-in-picture windows especially), not a WebView
        // crash. Reset the counter HERE (in addition to onPageFinished and
        // onDestroy) so background kills stop accumulating toward the
        // WebView-picker bounce — the "app closes itself right after I
        // open it" symptom.
        // A genuine crash-loop (broken WebView provider) still trips the
        // bounce: those launches die while foregrounded, BEFORE the first
        // pause and before the first page load.
        CrashTracker.reset();
        // Pause JS timers/layout for all our WebViews while backgrounded —
        // previously a streaming chat kept running (and draining battery) in
        // the background.
        if (mainWebViewController != null) mainWebViewController.pause(webview);
        if (popupAuthController != null) popupAuthController.pauseAll();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Persist the WebView navigation state whenever the system asks us
        // to (configuration-driven recreation, memory-pressure activity
        // destroy, process death). onCreate's restore path uses it to
        // resume the open conversation instead of the homepage.
        if (mainWebViewController != null) {
            mainWebViewController.saveNavigationState(webview, outState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mainWebViewController != null) mainWebViewController.resume(webview);
        if (popupAuthController != null) popupAuthController.resumeAll();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // Activity is about to be backgrounded — capture a snapshot
            // NOW (before onPause, while the surface is still alive) so we
            // can mask the black flash on resume.
            captureResumeSnapshot();
        } else {
            // Activity just regained focus — schedule the snapshot to fade
            // out 300ms from now, giving the WebView time to repaint.
            scheduleSnapshotFadeOut(300);
        }
    }

    /**
     * Capture a bitmap of the FULL WINDOW (DecorView: WebView, any open
     * popups, and the status-bar strip) and display it on top via
     * {@link #resumeSnapshot}. Uses PixelCopy on API 26+ (reliable on
     * hardware-accelerated views) and falls back to a software canvas draw on
     * older API levels. The bitmap is sized to the DecorView — the SAME
     * geometry PixelCopy.request(getWindow(), ...) captures and the SAME
     * geometry the overlay occupies — so it is displayed 1:1 with no scaling.
     * (v6.24.28/29 sized the bitmap to rootLayout but copied the whole
     * window, then showed it inside a rootLayout-sized overlay: the bitmap
     * was squeezed down by the status-bar height, producing a shrunken app
     * with a second black status-bar strip for ~300ms on resume.)
     */
    private void captureResumeSnapshot() {
        if (resumeSnapshot == null) return;
        View decor = getWindow() != null ? (View) getWindow().getDecorView() : null;
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            return;
        }
        final int w = decor.getWidth();
        final int h = decor.getHeight();
        // Hide the snapshot ImageView itself while capturing so we don't
        // recursively capture our own overlay.
        final int prevVis = resumeSnapshot.getVisibility();
        resumeSnapshot.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy (API 26+) is async and reliable on hardware-accelerated
            // views. Capture the whole window (PixelCopy.request only accepts
            // Surface / SurfaceView / Window — there is no View overload).
            try {
                final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                PixelCopy.request(getWindow(), bmp, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        runOnUiThread(() -> {
                            if (resumeSnapshotBitmap != null) {
                                resumeSnapshotBitmap.recycle();
                            }
                            resumeSnapshotBitmap = bmp;
                            resumeSnapshot.setImageBitmap(bmp);
                            resumeSnapshot.setVisibility(View.VISIBLE);
                            // Late-callback safety: if window focus has
                            // ALREADY been regained by the time this async
                            // copy completes, onWindowFocusChanged(true) has
                            // come and gone and nothing would schedule the
                            // fade-out — the overlay would be stuck on top
                            // forever. Schedule it here instead.
                            if (hasWindowFocus()) scheduleSnapshotFadeOut(300);
                        });
                    } else {
                        Log.w(TAG, "snapshot PixelCopy failed code=" + result);
                        // Restore previous visibility (probably GONE) on failure
                        runOnUiThread(() -> resumeSnapshot.setVisibility(prevVis));
                    }
                }, snapshotHandler);
            } catch (Throwable t) {
                Log.e(TAG, "PixelCopy threw", t);
                Log.w(TAG, "snapshot PixelCopy threw: " + t.getClass().getSimpleName());
                resumeSnapshot.setVisibility(prevVis);
            }
        } else {
            // Pre-API-26 fallback: software canvas draw. Less reliable for
            // GPU-rendered content but better than nothing.
            try {
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                decor.draw(canvas);
                if (resumeSnapshotBitmap != null) {
                    resumeSnapshotBitmap.recycle();
                }
                resumeSnapshotBitmap = bmp;
                resumeSnapshot.setImageBitmap(bmp);
                resumeSnapshot.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                Log.e(TAG, "snapshot draw threw", t);
                resumeSnapshot.setVisibility(prevVis);
            }
        }
    }

    /**
     * Schedule the resume snapshot overlay to fade out and hide after
     * {@code delayMs} milliseconds. Idempotent — multiple calls in flight
     * will just keep rescheduling.
     */
    private void scheduleSnapshotFadeOut(int delayMs) {
        if (resumeSnapshot == null || resumeSnapshot.getVisibility() != View.VISIBLE) {
            return;
        }
        snapshotHandler.removeCallbacksAndMessages(null);
        snapshotHandler.postDelayed(() -> {
            runOnUiThread(() -> {
                if (resumeSnapshot != null
                        && resumeSnapshot.getVisibility() == View.VISIBLE) {
                    resumeSnapshot.setVisibility(View.GONE);
                    resumeSnapshot.setImageBitmap(null);
                    if (resumeSnapshotBitmap != null) {
                        resumeSnapshotBitmap.recycle();
                        resumeSnapshotBitmap = null;
                    }
                }
            });
        }, delayMs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is now in configChanges — re-tint the loading logo ourselves
        // instead of letting the activity (and WebView) be recreated.
        if (loadingLogo != null) {
            boolean dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            loadingLogo.setImageResource(dark ? R.drawable.logo_white : R.drawable.logo_black);
        }
        // Re-apply the window + rootLayout background color so it tracks the
        // new theme — otherwise a dark→light switch would leave the window
        // pinned to dark grey (Bug-2 mask) while the page goes white.
        applyBackgroundColors();
        // Same story for the status / navigation bars: the theme set their
        // colors at window creation and the activity is NOT recreated
        // (uiMode is in configChanges), so without this a live light↔dark
        // switch left the bars — and their icon tint — in the OLD mode
        // while the website had already adapted on its own (v6.28 fix).
        applySystemBarColors();
    }

    @Override
    public void onBackPressed() {
        if (popupAuthController != null && popupAuthController.closeTopPopup()) {
            return;
        }
        if (mainWebViewController == null
                || !mainWebViewController.goBackIfPossible(webview)) {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // A clean destroy is not a crash — reset the counter so abandoned
        // launches (no signal, swipe-away) no longer count toward the
        // "pick another WebView" bounce.
        CrashTracker.reset();
        if (popupAuthController != null) {
            popupAuthController.destroyAll();
        }
        if (transferController != null) {
            transferController.destroy();
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
        }
        stopLoadingLogoAnimation();
        if (mainWebViewController != null) {
            mainWebViewController.destroyMainWebView(webview);
        } else if (webview != null) {
            try {
                webview.destroy();
            } catch (Throwable t) {
                Log.e(TAG, "fallback WebView destroy failed", t);
            }
        }
        webview = null;
        filePathCallback = null;
        pendingShareFileUri = null;
        super.onDestroy();
    }


}
