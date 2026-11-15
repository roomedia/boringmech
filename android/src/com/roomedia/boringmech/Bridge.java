package com.roomedia.boringmech;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import org.json.JSONObject;

// WebView ↔ 네이티브 다리. 같은 페이지가 서비스(엔진 호스트)와 액티비티
// (뷰어) 양쪽에서 뜨므로, 호스트별 차이는 Host 콜백으로만 가르고
// 나머지(실행 의도 미러·하트비트·스냅샷 캐시·주문 전송·업데이트)는 공용이다.
public class Bridge {
    public interface Host {
        String hostMode();

        // 실행 의도가 바뀐 뒤의 호스트별 부수효과 (메인 스레드에서 불린다).
        // 뷰어: 서비스를 올리거나 내린다. 엔진 호스트: 정지 시 서비스 종료.
        void onRunIntentChanged(boolean on);

        // 뷰어에서 연동이 바뀌었을 때 서비스 엔진 페이지를 다시 읽힌다.
        void onReloadEngine();
    }

    private final Context ctx;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    public Bridge(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
    }

    @JavascriptInterface
    public String hostMode() {
        return host.hostMode();
    }

    // 실행 의도의 단일 기록 지점 — 미러(SharedPreferences)에 먼저 남기고
    // 호스트 부수효과는 메인 스레드로 넘긴다.
    @JavascriptInterface
    public void setRunIntent(final boolean on) {
        EngineService.setRunIntent(ctx, on);
        main.post(() -> host.onRunIntentChanged(on));
    }

    @JavascriptInterface
    public boolean getRunIntent() {
        return EngineService.getRunIntent(ctx);
    }

    // 워치독 하트비트 — 엔진이 사이클마다 남긴다.
    @JavascriptInterface
    public void heartbeat(int cycle) {
        EngineService.recordHeartbeat(ctx, cycle);
    }

    @JavascriptInterface
    public long getHeartbeatMs() {
        return EngineService.getHeartbeatMs(ctx);
    }

    @JavascriptInterface
    public int getWatchdogReloads() {
        return EngineService.getWatchdogReloads(ctx);
    }

    // 워치독 재로드 중 사이클 정체가 사유였던 횟수 (구분 기록용)
    @JavascriptInterface
    public int getWatchdogCycleReloads() {
        return EngineService.getWatchdogCycleReloads(ctx);
    }

    // 스냅샷 캐시 미러 — localStorage와 별개로 확실히 공유되는 통로.
    @JavascriptInterface
    public void putSnapCache(String json) {
        EngineService.putSnapCache(ctx, json);
    }

    @JavascriptInterface
    public String getSnapCache() {
        return EngineService.getSnapCache(ctx);
    }

    @JavascriptInterface
    public void reloadEngine() {
        main.post(host::onReloadEngine);
    }

    // 셀프 업데이트 — 어느 호스트에서 눌러도 같은 기계장치로 받는다.
    @JavascriptInterface
    public void downloadAndInstall(final String url, final String fileName,
                                   final String sha256) {
        main.post(() -> SelfUpdate.startDownload(ctx, url, fileName, sha256));
    }

    // 주문 POST 전용 동기 전송 — CLOB /order·/orders의 실제 응답은
    // Access-Control-Allow-Origin이 https://polymarket.com 고정이라
    // WebView(오리진 null)가 응답 읽기를 차단한다. 서명·헤더·본문은
    // SDK가 만든 것을 그대로 받아 네이티브로 보내 CORS를 우회한다.
    // @JavascriptInterface는 백그라운드 스레드에서 불리므로 동기 수행이 안전하다.
    // 반환: {"status": <HTTP 코드, 네트워크 실패 시 0>, "body": <응답 원문>,
    //        "error": <전송 실패 사유, 있을 때만>}
    @JavascriptInterface
    public String postSync(final String url, final String headersJson,
                           final String bodyJson) {
        int status = 0;
        String body = "";
        String error = null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setDoOutput(true);
            if (headersJson != null) {
                JSONObject headers = new JSONObject(headersJson);
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    conn.setRequestProperty(k, headers.optString(k, ""));
                }
            }
            conn.setRequestProperty("Content-Type", "application/json");
            byte[] payload = (bodyJson == null ? "" : bodyJson)
                    .getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            OutputStream os = conn.getOutputStream();
            os.write(payload);
            os.close();
            status = conn.getResponseCode();
            InputStream in = status >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                in.close();
                body = buf.toString("UTF-8");
            }
        } catch (Exception e) {
            status = 0;
            body = "";
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
        try {
            JSONObject out = new JSONObject();
            out.put("status", status);
            out.put("body", body);
            if (error != null) out.put("error", error);
            return out.toString();
        } catch (Exception e) {
            return "{\"status\":0,\"body\":\"\",\"error\":\"bridge encode failed\"}";
        }
    }

    // 동기 GET — relayer 조회처럼 커스텀 헤더가 필요한 GET도 WebView fetch의
    // CORS에 걸릴 수 있어 주문 POST와 같은 통로로 보낸다. 반환 형식은 postSync와 같다.
    // 실제 전송은 static 코어가 담당해 서비스의 네이티브 섀도우도 같은
    // 방식(타임아웃·응답 봉투)으로 재사용한다.
    @JavascriptInterface
    public String getSync(final String url, final String headersJson) {
        return getSyncRaw(url, headersJson);
    }

    static String getSyncRaw(final String url, final String headersJson) {
        int status = 0;
        String body = "";
        String error = null;
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            if (headersJson != null) {
                JSONObject headers = new JSONObject(headersJson);
                Iterator<String> keys = headers.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    conn.setRequestProperty(k, headers.optString(k, ""));
                }
            }
            status = conn.getResponseCode();
            InputStream in = status >= 400
                    ? conn.getErrorStream() : conn.getInputStream();
            if (in != null) {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int n;
                while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
                in.close();
                body = buf.toString("UTF-8");
            }
        } catch (Exception e) {
            status = 0;
            body = "";
            error = e.getMessage() != null ? e.getMessage() : e.toString();
        } finally {
            if (conn != null) conn.disconnect();
        }
        try {
            JSONObject out = new JSONObject();
            out.put("status", status);
            out.put("body", body);
            if (error != null) out.put("error", error);
            return out.toString();
        } catch (Exception e) {
            return "{\"status\":0,\"body\":\"\",\"error\":\"bridge encode failed\"}";
        }
    }

    // 돈 이벤트 알림 — 엔진이 체결·정산·손절 시점에 부른다.
    // 본문 구성(돈 정보만, 종목명 금지)은 JS가 책임진다.
    private static final String EVENTS_CHANNEL = "bm_events";
    private static final String NOTIFY_PREFS = "bm_notify";

    @JavascriptInterface
    public void notify(final String title, final String body) {
        main.post(() -> {
            try {
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel(
                            EVENTS_CHANNEL, "거래 알림", NotificationManager.IMPORTANCE_DEFAULT);
                    nm.createNotificationChannel(ch);
                }
                SharedPreferences prefs =
                        ctx.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE);
                int id = prefs.getInt("next_id", 100) + 1;
                prefs.edit().putInt("next_id", id).apply();
                Notification n = new Notification.Builder(ctx, EVENTS_CHANNEL)
                        .setSmallIcon(ctx.getApplicationInfo().icon)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setAutoCancel(true)
                        .build();
                nm.notify(id, n);
            } catch (Exception ignored) {
            }
        });
    }

    // 영속 미러 (부수1) — localStorage의 비동기 디스크 반영으로 사라질 수
    // 있는 돈 관련 기록(매수·손절·리딤·정산 기준선)을 SharedPreferences에
    // 동기(commit)로 이중 기록한다. 키는 bm_ 접두사인 것만 받는다.
    private static final String DURABLE_PREFS = "bm_durable";

    @JavascriptInterface
    public void putDurable(final String key, final String value) {
        if (key == null || value == null || !key.startsWith("bm_")) return;
        ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(key, value).commit();
    }

    @JavascriptInterface
    public String getDurable(final String key) {
        if (key == null || !key.startsWith("bm_")) return null;
        return ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE)
                .getString(key, null);
    }

    @JavascriptInterface
    public void removeDurable(final String key) {
        if (key == null || !key.startsWith("bm_")) return;
        ctx.getSharedPreferences(DURABLE_PREFS, Context.MODE_PRIVATE)
                .edit().remove(key).commit();
    }

    // 제출 진행 가드 (F1) — 제출이 도는 동안 워치독이 엔진 페이지를
    // 재로드하지 않게 서비스와 공유하는 플래그.
    @JavascriptInterface
    public void setSubmitInFlight(final boolean on) {
        EngineService.setSubmitInFlight(ctx, on);
    }

    // 비밀 암호화 — Android Keystore의 AES-GCM 키로 암호화해 base64(IV‖암호문)로
    // 돌려준다. 저장소(localStorage)에는 암호문만 남고, 키 자체는 Keystore 밖으로
    // 나오지 않는다. 실패하면 null — 호출자는 평문 저장으로 떨어지지 않는다.
    private static final String SECRET_ALIAS = "bm_secret_key";
    // 키 확인-생성의 동기화 (F4) — 두 WebView의 브리지 스레드가 동시에
    // 첫 마이그레이션을 돌려도 키가 두 번 생성·교체되지 않게 한다.
    // 프로세스 단위 static 락이라 서비스·액티비티의 Bridge 인스턴스가 공유한다.
    private static final Object SECRET_KEY_LOCK = new Object();

    private static SecretKey secretKey() throws Exception {
        synchronized (SECRET_KEY_LOCK) {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(SECRET_ALIAS)) {
                return ((KeyStore.SecretKeyEntry) ks.getEntry(SECRET_ALIAS, null)).getSecretKey();
            }
            KeyGenerator kg = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            kg.init(new KeyGenParameterSpec.Builder(
                    SECRET_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build());
            return kg.generateKey();
        }
    }

    @JavascriptInterface
    public String encryptSecret(final String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    @JavascriptInterface
    public String decryptSecret(final String encoded) {
        return decryptSecretText(encoded);
    }

    // 복호화 코어 — 서비스의 네이티브 섀도우가 저장된 creds를 풀 때도 쓴다.
    static String decryptSecretText(final String encoded) {
        try {
            byte[] raw = Base64.decode(encoded, Base64.NO_WRAP);
            if (raw.length <= 12) return null;
            byte[] iv = new byte[12];
            System.arraycopy(raw, 0, iv, 0, 12);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, iv));
            byte[] pt = cipher.doFinal(raw, 12, raw.length - 12);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    // ── 네이티브 섀도우용 미러 ──────────────────────────────────────
    // 섀도우 스냅샷(읽기 전용 대조)은 WebView localStorage에 접근할 수
    // 없어, JS가 boot/link 시점에 funder와 creds 암호문을 네이티브
    // 미러(bm_engine)에도 남긴다. creds는 암호문 그대로만 보관하고
    // 복호화는 조회 시점에만 한다 — 평문을 prefs에 두지 않는다.

    @JavascriptInterface
    public void setFunderMirror(final String funder) {
        EngineService.setFunder(ctx, funder);
    }

    // eoa는 L2 헤더의 POLY_ADDRESS용 공개 주소, cipher는 localStorage의
    // bm_creds 원문("enc1:" 접두사 포함 가능). 형식이 아니면 미러를 비운다.
    @JavascriptInterface
    public void setCredsMirror(final String eoa, final String cipher) {
        EngineService.setEoa(ctx, eoa);
        EngineService.setCredsCipher(ctx, cipher);
    }

    /** 섀도우가 쓰는 자격증명 — 복호화된 값은 이 객체 안에서만 산다. */
    public static final class Creds {
        public final String eoa;
        public final String key;
        public final String secret;
        public final String passphrase;

        Creds(String eoa, String key, String secret, String passphrase) {
            this.eoa = eoa;
            this.key = key;
            this.secret = secret;
            this.passphrase = passphrase;
        }
    }

    // 미러된 creds 암호문을 복호화해 돌려준다. 미러가 없거나 복호화·형식이
    // 실패하면 null — 섀도우는 그 회차를 오류로 기록하고 건너뛴다.
    static Creds loadShadowCreds(Context ctx) {
        String eoa = EngineService.getEoa(ctx);
        String cipher = EngineService.getCredsCipher(ctx);
        if (eoa == null || cipher == null) return null;
        String plain = cipher.startsWith(SECRET_PREFIX)
                ? decryptSecretText(cipher.substring(SECRET_PREFIX.length()))
                : cipher; // 마이그레이션 전 레거시 평문 (JS readSecret와 같은 취급)
        if (plain == null) return null;
        try {
            JSONObject o = new JSONObject(plain);
            String key = o.optString("key", "");
            String secret = o.optString("secret", "");
            String passphrase = o.optString("passphrase", "");
            if (key.isEmpty() || secret.isEmpty() || passphrase.isEmpty()) return null;
            return new Creds(eoa, key, secret, passphrase);
        } catch (Exception e) {
            return null;
        }
    }

    private static final String SECRET_PREFIX = "enc1:";

    // 뷰어 표시용 섀도우 대조 상태 (bm_shadow prefs의 JSON 그대로).
    @JavascriptInterface
    public String getShadowStatus() {
        return EngineService.getShadowStatusJson(ctx);
    }
}
