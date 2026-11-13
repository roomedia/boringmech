package com.roomedia.boringmech;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView web;
    private BroadcastReceiver engineStopReceiver;

    // WebView 엔진과의 다리 — 실행 의도를 네이티브 미러에 남기고
    // 포그라운드 서비스를 엔진 상태에 맞춰 올리고 내린다.
    public class Bridge {
        @JavascriptInterface
        public void setRunIntent(final boolean on) {
            runOnUiThread(() -> {
                EngineService.setRunIntent(MainActivity.this, on);
                Intent service = new Intent(MainActivity.this, EngineService.class);
                if (on) {
                    if (Build.VERSION.SDK_INT >= 26) {
                        startForegroundService(service);
                    } else {
                        startService(service);
                    }
                } else {
                    stopService(service);
                }
            });
        }

        @JavascriptInterface
        public boolean getRunIntent() {
            return EngineService.getRunIntent(MainActivity.this);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = new WebView(this);
        setContentView(web);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://")) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }
        });

        // 알림의 정지 액션이 서비스로 들어오면 여기로 브로드캐스트가 온다
        engineStopReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                web.evaluateJavascript("window.BM && BM.stop();", null);
            }
        };
        IntentFilter filter = new IntentFilter(EngineService.ACTION_ENGINE_STOP_REQUEST);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(engineStopReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(engineStopReceiver, filter);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onDestroy() {
        if (engineStopReceiver != null) {
            unregisterReceiver(engineStopReceiver);
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
