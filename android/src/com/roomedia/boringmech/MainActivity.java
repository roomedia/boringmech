package com.roomedia.boringmech;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

// 뷰어/컨트롤러 액티비티 — 엔진 WebView는 EngineService가 소유하고,
// 이 화면은 엔진이 남긴 상태를 표시하고 실행 의도만 바꾼다.
public class MainActivity extends Activity {
    private WebView web;
    private BroadcastReceiver downloadReceiver;

    private final Bridge.Host viewerHost = new Bridge.Host() {
        @Override
        public String hostMode() {
            return "viewer";
        }

        @Override
        public void onRunIntentChanged(boolean on) {
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
        }

        @Override
        public void onReloadEngine() {
            sendBroadcast(new Intent(EngineService.ACTION_RELOAD_ENGINE)
                    .setPackage(getPackageName()));
        }
    };

    private WebView buildWebView() {
        WebView w = new WebView(this);
        WebSettings settings = w.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        w.addJavascriptInterface(new Bridge(this, viewerHost), "AndroidBridge");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url.startsWith("file://")) {
                    return false;
                }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return true;
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                                RenderProcessGoneDetail detail) {
                // 렌더러가 죽은 WebView는 버리고 새로 만든다 (F6)
                view.destroy();
                web = buildWebView();
                setContentView(web);
                web.loadUrl("file:///android_asset/index.html");
                return true;
            }
        });
        return w;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        web = buildWebView();
        setContentView(web);

        // 업데이트 APK 다운로드 완료 알림 (서비스와 같은 기계장치로 위임 —
        // 완료 처리는 SelfUpdate 안에서 한 번만 실행된다)
        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                SelfUpdate.onDownloadComplete(MainActivity.this,
                        intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1));
            }
        };
        IntentFilter dlFilter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(downloadReceiver, dlFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(downloadReceiver, dlFilter);
        }

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        }

        // 실행 의도가 켜져 있는데 서비스가 죽어 있으면 다시 세운다 —
        // 외부 감시의 재기동이 시스템 제한으로 거부된 경우에도 앱을
        // 여는 것이 실제 재개 경로가 되게 한다 (v0.3.35 감사 축 4).
        // 서비스가 이미 살아 있으면 onStartCommand가 멱등하게 처리한다.
        if (EngineService.getRunIntent(this)) {
            Intent service = new Intent(this, EngineService.class);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        }

        web.loadUrl("file:///android_asset/index.html");

        // 이전 실행에서 끝나지 않은 업데이트 다운로드가 있으면 이어서 처리
        SelfUpdate.restorePending(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SelfUpdate.restorePending(this);
    }

    @Override
    protected void onDestroy() {
        if (downloadReceiver != null) {
            unregisterReceiver(downloadReceiver);
        }
        // WebView를 파괴하지 않으면 구 뷰어의 타이머·브리지가 액티비티
        // 컨텍스트를 물고 남는다 (부수5)
        if (web != null) {
            web.destroy();
            web = null;
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
