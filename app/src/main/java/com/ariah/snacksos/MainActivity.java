package com.ariah.snacksos;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;

public class MainActivity extends Activity {
    private WebView web;
    private boolean inFlow = false;
    private boolean repinOnReturn = false;

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
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception e) {}
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);
    }

    private boolean isPinned() {
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
    }

    @Override protected void onResume() {
        super.onResume();
        if (repinOnReturn && inFlow) {
            repinOnReturn = false;
            try { startLockTask(); } catch (Exception e) {}
        }
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    class Bridge {
        @JavascriptInterface public void startPin() {
            runOnUiThread(() -> {
                inFlow = true;
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { startLockTask(); } catch (Exception e) {}
            });
        }
        @JavascriptInterface public void stopPin() {
            runOnUiThread(() -> {
                inFlow = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { if (isPinned()) stopLockTask(); } catch (Exception e) {}
            });
        }
        @JavascriptInterface public void openNoom() {
            runOnUiThread(() -> {
                try { if (isPinned()) { stopLockTask(); repinOnReturn = true; } } catch (Exception e) {}
                Intent i = getPackageManager().getLaunchIntentForPackage("com.wsl.noom");
                if (i == null) i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.wsl.noom"));
                try { startActivity(i); } catch (Exception e) {}
            });
        }
    }
}
