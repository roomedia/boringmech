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

import com.roomedia.boringmech.write.RedeemSubmitter;

// WebView ↔ 네이티브 다리. 페이지는 액티비티(뷰어)에서만 뜨고, 엔진은
// 네이티브 스레드(NativeEngine)가 돌린다. 호스트별 차이는 Host 콜백으로만
// 가르고 나머지(실행 의도 미러·하트비트·스냅샷 캐시·주문 전송·업데이트)는
// 공용이다. 네이티브 엔진은 이 클래스의 static 코어(비밀 복호·알림·미러
// 조회)를 직접 쓴다.
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

    // 사이클 내부 진행 신호 — 루프가 단계·후보를 처리할 때만 불린다
    // (30초 타이머 하트비트와 별개). 워치독의 사이클 정체 판정이
    // "번호는 안 바뀌었지만 진행 중인 긴 사이클"을 오재로드하지
    // 않게 하는 재료다.
    @JavascriptInterface
    public void cycleProgress(int cycle) {
        EngineService.recordProgress(ctx);
    }

    // 제보 payload용 네이티브 상태 JSON — 워치독 카운터·하트비트·
    // 부팅 실패·틱 생존 시각을 한 번에 돌려준다.
    @JavascriptInterface
    public String getNativeState() {
        return EngineService.getNativeStateJson(ctx);
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
    // 채널은 둘로 나뉜다: 거래(체결·정산, 기본 중요도)와 오류(사용자
    // 행동이 필요하거나 거래에 영향이 있는 오류, 높은 중요도).
    // 알림 권한이 없으면 조용히 건너뛰고 로그만 남긴다 — 권한 요청
    // UI는 만들지 않는다 (뷰어에 설정 안내 한 줄만 있다).
    // 발송 실패는 어떤 경우에도 호출자(거래 흐름)로 전파하지 않는다.
    private static final String EVENTS_CHANNEL = "bm_events";
    private static final String ERROR_CHANNEL = "bm_errors";
    private static final String NOTIFY_PREFS = "bm_notify";

    @JavascriptInterface
    public void notify(final String title, final String body) {
        notifyMoney(ctx, title, body);
    }

    // 네이티브 엔진도 같은 경로로 알림을 보낸다 (본문 구성은 호출자 책임).
    static void notifyMoney(final Context context, final String title, final String body) {
        postNotification(context, EVENTS_CHANNEL, "거래 알림",
                NotificationManager.IMPORTANCE_DEFAULT, title, body);
    }

    /**
     * 오류 알림 — 같은 사유 키는 NotifyPolicy 간격(30분) 안에 1회만
     * 나간다. dedupe 상태는 bm_notify prefs가 보관한다.
     */
    static void notifyError(final Context context, final String key,
                            final String title, final String body) {
        // 권한이 없어 어차피 건너뛸 발송이면 dedupe를 기록하지 않는다 —
        // 기록해 두면 권한을 켠 뒤에도 30분간 같은 사유가 침묵한다
        // (NotifyPolicy 규약: 실제로 보냈을 때만 기록).
        try {
            NotificationManager nm = (NotificationManager)
                    context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || !notificationsAllowed(context, nm)) {
                android.util.Log.i("boringmech", "알림 권한 없음 — 오류 알림 건너뜀 (dedupe 미기록): " + title);
                return;
            }
        } catch (Exception ignored) {
            // 확인이 깨지면 발송 시도로 떨어진다 (postNotification이 다시 막는다)
        }
        try {
            SharedPreferences prefs =
                    context.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE);
            java.util.Map<String, Long> last = new java.util.HashMap<>();
            for (java.util.Map.Entry<String, ?> e : prefs.getAll().entrySet()) {
                if (e.getKey().startsWith("err_last_") && e.getValue() instanceof Long) {
                    last.put(e.getKey().substring("err_last_".length()), (Long) e.getValue());
                }
            }
            long now = System.currentTimeMillis();
            if (!com.roomedia.boringmech.write.NotifyPolicy.shouldSendError(last, key, now)) {
                return;
            }
            com.roomedia.boringmech.write.NotifyPolicy.markErrorSent(last, key, now);
            if (key != null) {
                prefs.edit().putLong("err_last_" + key, now).apply();
            }
        } catch (Exception ignored) {
            // dedupe 기록이 깨져도 알림 자체는 시도한다
        }
        postNotification(context, ERROR_CHANNEL, "오류 알림",
                NotificationManager.IMPORTANCE_HIGH, title, body);
    }

    private static void postNotification(final Context context, final String channelId,
                                         final String channelName, final int importance,
                                         final String title, final String body) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                NotificationManager nm = (NotificationManager)
                        context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm == null) return;
                if (!notificationsAllowed(context, nm)) {
                    android.util.Log.i("boringmech", "알림 권한 없음 — 건너뜀: " + title);
                    return;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    NotificationChannel ch = new NotificationChannel(
                            channelId, channelName, importance);
                    nm.createNotificationChannel(ch);
                }
                SharedPreferences prefs =
                        context.getSharedPreferences(NOTIFY_PREFS, Context.MODE_PRIVATE);
                int id = prefs.getInt("next_id", 100) + 1;
                prefs.edit().putInt("next_id", id).apply();
                Notification n = new Notification.Builder(context, channelId)
                        .setSmallIcon(context.getApplicationInfo().icon)
                        .setContentTitle(title)
                        .setContentText(body)
                        .setStyle(new Notification.BigTextStyle().bigText(body))
                        .setAutoCancel(true)
                        .build();
                nm.notify(id, n);
            } catch (Exception ignored) {
            }
        });
    }

    /** 알림을 실제로 띄울 수 있는 상태인가 — 권한·전역 스위치 확인. */
    private static boolean notificationsAllowed(Context context, NotificationManager nm) {
        if (!nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return true;
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
        return encryptSecretText(plain);
    }

    // 암호화 코어 — 네이티브 엔진이 파생한 creds를 미러에 저장할 때도 쓴다.
    static String encryptSecretText(final String plain) {
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

    // ── 네이티브 엔진용 미러 ────────────────────────────────────────
    // 네이티브 엔진은 WebView localStorage에 접근할 수 없어, JS가
    // boot/link 시점에 funder·creds·개인키·Relayer 키 암호문을 네이티브
    // 미러(bm_engine)에도 남긴다. 암호문 그대로만 보관하고 복호화는
    // 조회 시점에만 한다 — 평문을 prefs에 두지 않는다.

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

    // 네이티브 엔진(전환 통합)은 개인키와 Relayer 키도 미러로 받는다.
    // 둘 다 LS 원문(암호문) 그대로만 보관하고 복호화는 조회 시점에만 한다.
    @JavascriptInterface
    public void setPkMirror(final String cipher) {
        EngineService.setPkCipher(ctx, cipher);
    }

    @JavascriptInterface
    public void setRelayerMirror(final String cipher) {
        EngineService.setRelayerCipher(ctx, cipher);
    }

    // 연동 해제(unlink) — 비밀 미러를 전부 비운다. funder·eoa도 함께 지워
    // 네이티브 엔진이 이전 계정의 자격증명으로 복원되지 않게 한다.
    @JavascriptInterface
    public void clearSecretMirrors() {
        EngineService.setPkCipher(ctx, null);
        EngineService.setCredsCipher(ctx, null);
        EngineService.setRelayerCipher(ctx, null);
        EngineService.setFunder(ctx, null);
        EngineService.setEoa(ctx, null);
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

    /** 네이티브 엔진 복원용 비밀 묶음 — 복호화된 값은 이 객체 안에서만 산다. */
    public static final class NativeSecrets {
        public final String privateKey;
        public final Creds creds;
        public final RedeemSubmitter.RelayerCreds relayer;
        public final String funder;

        NativeSecrets(String privateKey, Creds creds,
                      RedeemSubmitter.RelayerCreds relayer, String funder) {
            this.privateKey = privateKey;
            this.creds = creds;
            this.relayer = relayer;
            this.funder = funder;
        }
    }

    private static String decryptMirror(String cipher) {
        if (cipher == null) return null;
        return cipher.startsWith(SECRET_PREFIX)
                ? decryptSecretText(cipher.substring(SECRET_PREFIX.length()))
                : cipher; // 마이그레이션 전 레거시 평문 (JS readSecret와 같은 취급)
    }

    // 개인키 미러가 없거나 복호화에 실패하면 null — 네이티브 엔진은 복원을
    // 보류하고 뷰어가 연동·미러를 마칠 때까지 기다린다.
    static NativeSecrets loadNativeSecrets(Context ctx) {
        String pk = decryptMirror(EngineService.getPkCipher(ctx));
        if (pk == null || pk.isEmpty()) return null;
        return new NativeSecrets(pk, loadShadowCreds(ctx), loadRelayerCreds(ctx),
                EngineService.getFunder(ctx));
    }

    // Relayer 키는 매 리딤 회차마다 새로 읽는다 (JS readRelayerCreds와 같다).
    static RedeemSubmitter.RelayerCreds loadRelayerCreds(Context ctx) {
        String plain = decryptMirror(EngineService.getRelayerCipher(ctx));
        if (plain == null) return null;
        try {
            JSONObject o = new JSONObject(plain);
            String key = o.optString("key", "");
            String address = o.optString("address", "");
            if (key.isEmpty() || address.isEmpty()) return null;
            return new RedeemSubmitter.RelayerCreds(key, address);
        } catch (Exception e) {
            return null;
        }
    }

    // 뷰어 표시용 섀도우 대조 상태 (bm_shadow prefs의 JSON 그대로).
    @JavascriptInterface
    public String getShadowStatus() {
        return EngineService.getShadowStatusJson(ctx);
    }

    // ── 네이티브 엔진 상태 (뷰어 읽기·승계) ─────────────────────────
    // 네이티브 엔진은 로그·stage·업데이트 정보를 bm_engine prefs에 남긴다.
    // 뷰어는 LS보다 이쪽을 우선 읽고, 첫 실행에는 LS 값을 한 번 승계한다.

    @JavascriptInterface
    public String getNativeLog() {
        return DurableStore.logJson(ctx);
    }

    @JavascriptInterface
    public boolean seedNativeLog(final String json) {
        return json != null && DurableStore.seedLog(ctx, json);
    }

    @JavascriptInterface
    public String getNativeStage() {
        return DurableStore.stageJson(ctx);
    }

    @JavascriptInterface
    public boolean seedNativeStage(final String json) {
        return json != null && DurableStore.seedStage(ctx, json);
    }

    @JavascriptInterface
    public String getNativeUpdateInfo() {
        return DurableStore.updateInfoJson(ctx);
    }
}
