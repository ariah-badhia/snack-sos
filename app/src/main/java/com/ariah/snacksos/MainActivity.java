package com.ariah.snacksos;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.StatusBarManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String ACTION_SOS = "com.ariah.snacksos.SOS";
    private static final int REQ_SAVE = 7;
    private static final String PAGE = "file:///android_asset/index.html";

    private WebView web;
    private boolean inFlow = false;
    private boolean repinOnReturn = false;
    private boolean noomOpened = false;
    private String pendingCsv = null;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(0xFF14161C);
        getWindow().setNavigationBarColor(0xFF14161C);
        web = new WebView(this);
        web.setBackgroundColor(0xFF14161C);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {}
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        // Tile or shortcut launch: skip the home screen and start breathing at once.
        boolean fresh = b == null && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0;
        web.loadUrl(fresh && wantsSos(getIntent()) ? PAGE + "#sos" : PAGE);
        setContentView(web);
    }

    private static boolean wantsSos(Intent i) {
        return i != null && (ACTION_SOS.equals(i.getAction()) || i.getBooleanExtra("sos", false));
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (wantsSos(i)) js("window.intentSOS&&intentSOS()");
    }

    private void js(String code) {
        web.evaluateJavascript(code, null);
    }

    private boolean isPinned() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (noomOpened) {
            noomOpened = false;
            js("window.noomReturned&&noomReturned()");
        }
        if (repinOnReturn && inFlow) {
            repinOnReturn = false;
            try { startLockTask(); } catch (Exception e) {}
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.onAndroidBack&&onAndroidBack())?1:0", v -> {
            if (!"1".equals(v)) finish();
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SAVE) return;
        String csv = pendingCsv;
        pendingCsv = null;
        if (res != RESULT_OK || data == null || data.getData() == null || csv == null) return;
        boolean ok = false;
        try (OutputStream out = getContentResolver().openOutputStream(data.getData())) {
            // The leading mark makes Excel and Sheets read emoji and accents correctly.
            out.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            ok = true;
        } catch (Exception e) {}
        js("window.exportDone&&exportDone(" + ok + ")");
    }

    class Bridge {
        @JavascriptInterface public void flowStart(boolean pinNow) {
            runOnUiThread(() -> {
                inFlow = true;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                if (pinNow) try { startLockTask(); } catch (Exception e) {}
            });
        }

        @JavascriptInterface public void pin() {
            runOnUiThread(() -> {
                try { if (inFlow && !isPinned()) startLockTask(); } catch (Exception e) {}
            });
        }

        @JavascriptInterface public void flowEnd() {
            runOnUiThread(() -> {
                inFlow = false;
                repinOnReturn = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { if (isPinned()) stopLockTask(); } catch (Exception e) {}
            });
        }

        @JavascriptInterface public void openNoom() {
            runOnUiThread(() -> {
                try { if (isPinned()) { stopLockTask(); repinOnReturn = true; } } catch (Exception e) {}
                noomOpened = true;
                Intent i = getPackageManager().getLaunchIntentForPackage("com.wsl.noom");
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.wsl.noom"));
                try { startActivity(i); } catch (Exception e) {}
            });
        }

        @JavascriptInterface public void buzz(String pattern) {
            try {
                String[] parts = pattern.split(",");
                long[] t = new long[parts.length + 1];
                t[0] = 0;
                for (int i = 0; i < parts.length; i++) t[i + 1] = Long.parseLong(parts[i].trim());
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(t, -1));
                else v.vibrate(t, -1);
            } catch (Exception e) {}
        }

        @JavascriptInterface public void saveCsv(String csv, String name) {
            runOnUiThread(() -> {
                pendingCsv = csv;
                Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("text/csv");
                i.putExtra(Intent.EXTRA_TITLE, name);
                try { startActivityForResult(i, REQ_SAVE); }
                catch (Exception e) { pendingCsv = null; js("window.exportDone&&exportDone(false)"); }
            });
        }

        @JavascriptInterface public void shareCsv(String csv, String name) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, name);
                i.putExtra(Intent.EXTRA_TEXT, csv);
                try { startActivity(Intent.createChooser(i, "Share Snack SOS history")); } catch (Exception e) {}
            });
        }

        @JavascriptInterface public void addTile() {
            runOnUiThread(() -> {
                if (Build.VERSION.SDK_INT < 33) {
                    js("window.tileResult&&tileResult('Swipe down twice, tap the pencil, and drag Snack SOS up')");
                    return;
                }
                try {
                    StatusBarManager sbm = getSystemService(StatusBarManager.class);
                    sbm.requestAddTileService(
                        new ComponentName(MainActivity.this, SosTileService.class),
                        "Snack SOS",
                        Icon.createWithResource(MainActivity.this, R.drawable.ic_tile),
                        getMainExecutor(),
                        r -> {
                            if (r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED)
                                js("window.tileResult&&tileResult('The tile is already in your panel')");
                            else if (r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED)
                                js("window.tileResult&&tileResult('Tile added')");
                        });
                } catch (Exception e) {
                    js("window.tileResult&&tileResult('Swipe down twice, tap the pencil, and drag Snack SOS up')");
                }
            });
        }
    }
}
