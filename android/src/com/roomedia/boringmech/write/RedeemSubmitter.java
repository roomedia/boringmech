package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.BatchSigner;
import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.crypto.Hex;
import com.roomedia.boringmech.engine.Js;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 리딤 Relayer 제출 — engine.js submitRedeemBatch + relayerGet/relayerPost의
 * 동등 이식.
 *
 * 흐름: GET /v1/account/transactions/params?address={eoa}&type=WALLET 로
 * nonce 조회 → Batch 서명(BatchSigner, DepositWallet 도메인) → POST /submit
 * → transactionID를 2초 간격으로 최대 120초 폴링해 STATE_CONFIRMED까지 확인.
 * nonce 불일치 오류가 오면 오류 문구의 on-chain nonce로 1회 재제출한다
 * (시도 상한 2회). 제출 본문 JSON의 필드 순서는 JS 객체 리터럴 순서 그대로
 * 수동 직렬화한다.
 *
 * Relayer API 키가 없으면(creds null) 감지·계획만 하고 제출은 보류한다 —
 * submitIfCreds가 네트워크를 전혀 타지 않고 HOLD를 돌려주는 것이 그 지점이다.
 * 전송은 Bridge postSync/getSync와 같은 봉투(RawHttp)를 쓴다: GET의 전송
 * 오류는 "relayer 조회 실패: …", POST의 전송 오류는 메시지 그대로 던진다
 * (원본의 비대칭을 그대로 둔다).
 */
public final class RedeemSubmitter {
    public static final String RELAYER_URL = "https://relayer-v2.polymarket.com";
    public static final String DEPOSIT_WALLET_FACTORY = "0x00000000000Fb5C9ADea0298D729A0CB3823Cc07";
    public static final int SUBMIT_ATTEMPTS = 2;
    public static final long POLL_MS = 2_000;
    public static final long POLL_MAX_MS = 120_000;
    public static final long DEADLINE_SECONDS = 600;

    private static final Pattern NONCE_MISMATCH =
            Pattern.compile("batch nonce (\\d+) does not match on-chain nonce (\\d+)");

    public static final class RelayerCreds {
        public final String key;
        public final String address;

        public RelayerCreds(String key, String address) {
            this.key = key;
            this.address = address;
        }
    }

    public static final class SubmitResult {
        public final String transactionId;
        public final String txHash;

        SubmitResult(String transactionId, String txHash) {
            this.transactionId = transactionId;
            this.txHash = txHash;
        }
    }

    public enum Gate {HOLD, READY}

    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    public interface Clock {
        long nowMs();
    }

    private final RawHttp.Transport transport;
    private final JsonCodec codec;
    private final BigInteger privateKey;
    private final String eoa;
    private final String funder;
    private final Sleeper sleeper;
    private final Clock clock;
    private final String relayerUrl;

    public RedeemSubmitter(RawHttp.Transport transport, JsonCodec codec,
                           BigInteger privateKey, String eoa, String funder,
                           Sleeper sleeper, Clock clock) {
        this(transport, codec, privateKey, eoa, funder, sleeper, clock, RELAYER_URL);
    }

    public RedeemSubmitter(RawHttp.Transport transport, JsonCodec codec,
                           BigInteger privateKey, String eoa, String funder,
                           Sleeper sleeper, Clock clock, String relayerUrl) {
        this.transport = transport;
        this.codec = codec;
        this.privateKey = privateKey;
        this.eoa = eoa;
        this.funder = funder;
        this.sleeper = sleeper;
        this.clock = clock;
        this.relayerUrl = relayerUrl;
    }

    /**
     * relayer 인증 실패(401) 판정 — 이 제출 경로가 만드는 오류 문구만 본다.
     * params 조회는 무인증 공개 엔드포인트라 401이 날 수 있는 인증 호출은
     * 제출(POST /submit)과 거래 조회뿐이고, 어느 쪽이든 키 쌍이 서버에서
     * 거부됐다는 뜻이라 같은 래치로 다룬다.
     */
    public static boolean isAuthError(String message) {
        if (message == null) {
            return false;
        }
        return message.contains("relayer 제출 HTTP 401")
                || message.contains("relayer 조회 HTTP 401");
    }

    /** 키 등록 여부 — runRedeems가 제출 보류를 가르는 것과 같은 판정. */
    public static Gate gate(RelayerCreds creds) {
        return creds == null ? Gate.HOLD : Gate.READY;
    }

    /**
     * 키가 없으면 HOLD(null 반환, 네트워크 없음), 있으면 제출까지 수행한다.
     * 호출자(runRedeems 등가)는 HOLD일 때 경고 로그와 함께 그 사이클
     * 제출을 건너뛴다.
     */
    public SubmitResult submitIfCreds(String conditionId, List<CallData> calls,
                                      RelayerCreds creds) throws Exception {
        if (creds == null) {
            return null;
        }
        return submitRedeemBatch(conditionId, calls, creds);
    }

    // ── relayer 전송 (Bridge 봉투 의미론) ──

    private Map<String, String> relayerHeaders(RelayerCreds creds) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("RELAYER_API_KEY", creds.key);
        h.put("RELAYER_API_KEY_ADDRESS", creds.address);
        h.put("Content-Type", "application/json");
        return h;
    }

    public Object relayerGet(String path, RelayerCreds creds) throws Exception {
        RawHttp.Raw out = transport.get(relayerUrl + path, relayerHeaders(creds));
        if (out.status == 0) {
            throw new Exception("relayer 조회 실패: " + out.error);
        }
        if (out.status >= 400) {
            throw new Exception("relayer 조회 HTTP " + out.status + ": " + slice(out.body, 160));
        }
        return codec.parse(out.body.isEmpty() ? "{}" : out.body);
    }

    public Object relayerPost(String path, String body, RelayerCreds creds) throws Exception {
        RawHttp.Raw out = transport.post(relayerUrl + path, relayerHeaders(creds), body);
        if (out.status == 0) {
            throw new Exception(slice(out.error == null ? "" : out.error, 200));
        }
        if (out.status >= 400) {
            throw new Exception("relayer 제출 HTTP " + out.status + ": " + slice(out.body, 200));
        }
        return codec.parse(out.body.isEmpty() ? "{}" : out.body);
    }

    // ── 제출 본문 ──

    /**
     * POST /submit 본문 — JS 객체 리터럴 순서 그대로:
     * {type, from, to, nonce, signature, metadata,
     *  depositWalletParams{depositWallet, deadline, calls[{target, value, data}]}}.
     * value는 문자열 "0"이다.
     */
    public static String submitBody(String eoa, String funder, String nonce, String signature,
                                    String conditionId, String deadline, List<CallData> calls) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"type\":\"WALLET\"");
        sb.append(",\"from\":").append(ModelJson.quote(eoa));
        sb.append(",\"to\":").append(ModelJson.quote(DEPOSIT_WALLET_FACTORY));
        sb.append(",\"nonce\":").append(ModelJson.quote(nonce));
        sb.append(",\"signature\":").append(ModelJson.quote(signature));
        sb.append(",\"metadata\":").append(
                ModelJson.quote("Redeem positions for condition " + conditionId));
        sb.append(",\"depositWalletParams\":{");
        sb.append("\"depositWallet\":").append(ModelJson.quote(funder));
        sb.append(",\"deadline\":").append(ModelJson.quote(deadline));
        sb.append(",\"calls\":[");
        for (int i = 0; i < calls.size(); i++) {
            CallData c = calls.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"target\":").append(ModelJson.quote(c.target));
            sb.append(",\"value\":\"0\"");
            sb.append(",\"data\":").append(ModelJson.quote(c.dataHex()));
            sb.append('}');
        }
        sb.append("]}}");
        return sb.toString();
    }

    // ── 배치 제출 + 폴링 ──

    public SubmitResult submitRedeemBatch(String conditionId, List<CallData> calls,
                                            RelayerCreds creds) throws Exception {
        Object params = relayerGet(
                "/v1/account/transactions/params?address=" + eoa + "&type=WALLET", creds);
        String nonce = Js.str(Js.field(params, "nonce"));
        Exception lastError = null;
        for (int attempt = 0; attempt < SUBMIT_ATTEMPTS; attempt += 1) {
            String deadline = String.valueOf(
                    Math.floorDiv(clock.nowMs(), 1000) + DEADLINE_SECONDS);
            String signature = BatchSigner.sign(privateKey, BigInteger.valueOf(137), funder,
                    new BigInteger(nonce), new BigInteger(deadline), calls);
            String body = submitBody(eoa, funder, nonce, signature, conditionId, deadline, calls);
            Object resp;
            try {
                resp = relayerPost("/submit", body, creds);
            } catch (Exception e) {
                lastError = e;
                Matcher m = NONCE_MISMATCH.matcher(e.getMessage() == null ? "" : e.getMessage());
                if (m.find() && new BigInteger(m.group(2)).compareTo(new BigInteger(nonce)) > 0) {
                    nonce = m.group(2);
                    continue;
                }
                throw e;
            }
            String txId = Js.str(Js.field(resp, "transactionID"));
            long started = clock.nowMs();
            while (clock.nowMs() - started < POLL_MAX_MS) {
                sleeper.sleep(POLL_MS);
                Object tx;
                try {
                    tx = relayerGet("/v1/account/transactions/" + txId, creds);
                } catch (Exception e) {
                    tx = null;
                }
                if (tx == null) {
                    continue;
                }
                String state = Js.str(Js.field(tx, "state"));
                if ("STATE_CONFIRMED".equals(state)) {
                    Object hash = Js.field(tx, "transaction_hash");
                    Object respHash = Js.field(resp, "transactionHash");
                    String txHash = Js.truthy(hash) ? Js.str(hash)
                            : (Js.truthy(respHash) ? Js.str(respHash) : null);
                    return new SubmitResult(txId, txHash);
                }
                if ("STATE_FAILED".equals(state) || "STATE_INVALID".equals(state)) {
                    Object errMsg = Js.field(tx, "error_msg");
                    throw new Exception("relayer 거래 실패 (" + state + ")"
                            + (Js.truthy(errMsg) ? ": " + Js.str(errMsg) : ""));
                }
            }
            throw new Exception("relayer 거래 확인 시간 초과 — 제출은 됐을 수 있어 다음 사이클에 잔고로 재확인");
        }
        throw lastError != null ? lastError : new Exception("relayer 제출 실패");
    }

    private static String slice(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }

    // Hex 재노출 (통합 단계 편의) — 서명 검증용
    static String hexOf(byte[] bytes) {
        return Hex.toHexPrefixed(bytes);
    }
}
