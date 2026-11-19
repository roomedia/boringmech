package com.roomedia.boringmech.write;

import com.roomedia.boringmech.crypto.JsNumber;
import com.roomedia.boringmech.engine.Js;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Map;

/**
 * 손절 매도 실행 — engine.js executeStopLossSell(+Locked)의 동등 이식.
 *
 * 흐름: slug 제출 잠금 → 제출 가드 → 조건부 토큰 잔고로 매도 수량 확정
 * (CLOB 잔고가 정본, 조회 실패 시 Data API 수량 유지) → 오더북 틱으로
 * 하한가 산출 → 시장가 매도 서명(createMarketOrder) → 제출(postOrder) →
 * judgeFill 판정 → 체결이면 원장 기록 + 알림.
 *
 * 실패 의미론 (원본 그대로):
 * - 서명 실패: 기록 없이 false (다음 사이클 재시도).
 * - 제출이 예외로 끊기거나 judgeFill이 unknown이면: 체결됐을 수 있어
 *   시도 토큰을 남기고 false (정산 판정 오염 방지).
 * - 명시 미체결·거부: 시도 토큰 없이 false, 응답 원문을 로그에 남긴다.
 * 클라이언트 메서드 호출은 각각 withClientTimeout 등가(TimeoutRunner)로
 * 감싼다 — ClobWriteClient 내부에도 있지만, 잔고·오더북 조회는 별도
 * 메서드라 여기서 감싼다.
 *
 * 수동 매도(executeManualSell)는 같은 실행 코어를 트리거만 바꿔 타는
 * 경로다 — 잠금 키(slug)·제출 가드·잔고 확정·하한가·FOK가 전부 같고,
 * 로그 접두사와 기록 장부만 "수동 매도"로 갈린다. 손절 원장에 섞으면
 * 정산 집계의 손절 건수와 재매수 차단 집합이 왜곡되기 때문이다.
 */
public final class StopLossSeller {
    /** SDK getBalanceAllowance(CONDITIONAL) 등가 — 원시 잔고(1e6 스케일) 반환. */
    public interface BalanceSource {
        BigInteger conditionalBalance(String tokenId) throws Exception;
    }

    /** SDK getOrderBook 등가 — 필요한 필드만. */
    public static final class OrderBookInfo {
        public final String tickSize;
        public final boolean negRisk;

        public OrderBookInfo(String tickSize, boolean negRisk) {
            this.tickSize = tickSize;
            this.negRisk = negRisk;
        }
    }

    public interface OrderBookSource {
        OrderBookInfo getOrderBook(String tokenId) throws Exception;

        String getTickSize(String tokenId) throws Exception;
    }

    public interface Logger {
        void log(String line);
    }

    public interface MoneyNotifier {
        void notifyMoney(String title, String body);
    }

    /**
     * 오류 알림 장치 — 서명 실패·결과 불명·명시 실패처럼 사용자가 알아야
     * 하는 거래 경로 오류만 나간다 (미체결 같은 확정 결과는 로그만).
     * 발송 실패가 매도 흐름을 바꾸면 안 되므로 호출 지점에서 전부 삼킨다.
     */
    public interface ErrorNotifier {
        void notifyError(String key, String title, String body);
    }

    public interface Clock {
        long nowMs();
    }

    /**
     * 수동 매도 원장 기록 장치 — 손절 원장(bm_stoploss)과 섞이면 정산
     * 집계의 손절 건수·재매수 차단이 왜곡되므로, 수동 매도 기록은 별도
     * 장부로 나간다. 통합 단계(NativeCycle)가 bm_durable 어댑터를 붙인다.
     */
    public interface ManualSellRecorder {
        void record(Map<String, Object> rec);

        void recordAttempt(String tokenId);
    }

    private final ClobWriteClient client;
    private final BalanceSource balanceSource;
    private final OrderBookSource orderBookSource;
    private final SubmitGuard guard;
    private final StopLossLedger.Store store;
    private final Logger logger;
    private final MoneyNotifier notifier;
    private final ClobWriteClient.TimeoutRunner timeout;
    private final Clock clock;
    private final ManualSellRecorder manualRecorder;
    private ErrorNotifier errorNotifier;

    /** 오류 알림 장치 연결 (선택) — 없으면 오류는 로그로만 남는다. */
    public void setErrorNotifier(ErrorNotifier errorNotifier) {
        this.errorNotifier = errorNotifier;
    }

    /** 오류 알림 — 발송 경로의 어떤 실패도 매도 흐름에 전파하지 않는다. */
    private void errorNotify(String key, String title, String body) {
        if (errorNotifier == null) {
            return;
        }
        try {
            errorNotifier.notifyError(key, title, body);
        } catch (Exception ignored) {
        }
    }

    public StopLossSeller(ClobWriteClient client, BalanceSource balanceSource,
                          OrderBookSource orderBookSource, SubmitGuard guard,
                          StopLossLedger.Store store, Logger logger,
                          MoneyNotifier notifier, ClobWriteClient.TimeoutRunner timeout,
                          Clock clock) {
        this(client, balanceSource, orderBookSource, guard, store, logger,
                notifier, timeout, clock, null);
    }

    public StopLossSeller(ClobWriteClient client, BalanceSource balanceSource,
                          OrderBookSource orderBookSource, SubmitGuard guard,
                          StopLossLedger.Store store, Logger logger,
                          MoneyNotifier notifier, ClobWriteClient.TimeoutRunner timeout,
                          Clock clock, ManualSellRecorder manualRecorder) {
        this.client = client;
        this.balanceSource = balanceSource;
        this.orderBookSource = orderBookSource;
        this.guard = guard;
        this.store = store;
        this.logger = logger;
        this.notifier = notifier;
        this.timeout = timeout;
        this.clock = clock;
        this.manualRecorder = manualRecorder;
    }

    /** 현재 세대 기준으로 실행하는 위임형 — 단독 사용·골든 테스트용. */
    public boolean executeStopLossSell(Map<String, Object> row, double current) {
        return executeStopLossSell(row, current, guard.currentGeneration());
    }

    /** executeStopLossSell — row는 Data API 포지션 행 모델(Map). gen은 호출 세션의 세대. */
    public boolean executeStopLossSell(Map<String, Object> row, double current, int gen) {
        return execute(row, current, gen, false);
    }

    /** 현재 세대 기준으로 실행하는 위임형 — 수동 매도. */
    public boolean executeManualSell(Map<String, Object> row, double current) {
        return executeManualSell(row, current, guard.currentGeneration());
    }

    /**
     * 수동 매도 — 손절과 같은 제출 경로(FOK·하한가·잔고 확정)를 타되,
     * 잠금 키(slug)·제출 가드를 공유하고 기록만 별도 장부로 나간다.
     * 기록 장치가 없으면 제출 자체를 하지 않는다 (기록 없는 매도 방지).
     */
    public boolean executeManualSell(Map<String, Object> row, double current, int gen) {
        if (manualRecorder == null) {
            logger.log("수동 매도 실행 불가 — 기록 장치가 없어 제출하지 않습니다 "
                    + Js.str(Js.field(row, "slug")));
            return false;
        }
        return execute(row, current, gen, true);
    }

    private boolean execute(Map<String, Object> row, double current, int gen, boolean manual) {
        String label = manual ? "수동 매도" : "손절";
        String slug = Js.str(Js.field(row, "slug"));
        if (!guard.tryAcquireSubmitLock(slug, gen)) {
            logger.log(label + " 제출 잠금 중 — 건너뜀 " + slug);
            return false;
        }
        try {
            if (!guard.beginSubmit(gen)) {
                logger.log(label + " 제출 중단 — 세션 세대 교체 " + slug);
                return false;
            }
            try {
                return executeLocked(row, current, manual);
            } catch (Exception e) {
                // 원본은 runStopLoss가 행 단위로 잡아 로그한다 — 여기서는
                // 같은 메시지로 남기고 false로 돌려준다.
                logger.log(label + " 처리 오류 " + slug + ": " + slice(msg(e), 120));
                return false;
            } finally {
                guard.endSubmit();
            }
        } finally {
            guard.releaseSubmitLock(slug);
        }
    }

    private boolean executeLocked(Map<String, Object> row, double current, boolean manual) throws Exception {
        String prefix = manual ? "수동 매도" : "손절 매도";
        String slug = Js.str(Js.field(row, "slug"));
        String tokenId = Js.str(Js.field(row, "token_id"));
        double entry = Js.fieldNum(row, "avg_price");
        double shares = Js.fieldNum(row, "current_size");
        try {
            BigInteger bal = timeout.run("getBalanceAllowance",
                    () -> balanceSource.conditionalBalance(tokenId));
            double exact = bal == null ? Double.NaN : bal.doubleValue() / 1e6;
            if (Double.isFinite(exact) && exact > 0) {
                shares = exact;
            }
        } catch (Exception ignored) {
            // 원본: catch {} — Data API 수량을 그대로 쓴다
        }
        if (!Double.isFinite(shares) || shares < 0.01) {
            logger.log((manual ? "수동 매도 건너뜀 " : "손절 건너뜀 ") + slug + " — 매도 가능 수량 부족 (" + JsNumber.toString(shares) + ")");
            return false;
        }
        OrderBookInfo book = timeout.run("getOrderBook", () -> orderBookSource.getOrderBook(tokenId));
        double tick;
        if (book.tickSize != null && !book.tickSize.isEmpty()) {
            tick = Double.parseDouble(book.tickSize);
        } else {
            String ts = timeout.run("getTickSize", () -> orderBookSource.getTickSize(tokenId));
            tick = Double.parseDouble(ts);
        }
        double floorPrice = StopLoss.floorPrice(current, tick);

        SignedOrder signed;
        try {
            ClobWriteClient.MarketOrderRequest req =
                    new ClobWriteClient.MarketOrderRequest(tokenId, false, shares, floorPrice);
            req.tickSize = JsNumber.toString(tick);
            req.negRisk = book.negRisk;
            signed = client.createMarketOrder(req);
        } catch (Exception e) {
            logger.log(prefix + " 서명 실패 " + slug + ": " + slice(msg(e), 120));
            errorNotify(NotifyPolicy.KEY_SIGN_FAIL, prefix + " 서명 실패",
                    slug + " · " + slice(msg(e), 120));
            return false;
        }
        Object resp;
        try {
            resp = client.postOrder(signed, "FOK");
        } catch (Exception e) {
            recordAttempt(tokenId, slug, manual);
            logger.log(prefix + " 결과 불명 " + slug + ": " + slice(msg(e), 120)
                    + (manual ? " — 체결 여부는 보유 목록에서 확인하세요" : " — 다음 사이클에 재확인"));
            errorNotify(NotifyPolicy.KEY_ORDER_UNKNOWN, prefix + " 결과 불명",
                    slug + " · 제출 응답이 끊겨 체결 여부를 모릅니다 — 보유 목록에서 확인하세요");
            return false;
        }
        JudgeFill.Verdict verdict = JudgeFill.judgeFill(resp);
        if (!verdict.filled) {
            if ("unknown".equals(verdict.kind)) {
                recordAttempt(tokenId, slug, manual);
                errorNotify(NotifyPolicy.KEY_ORDER_UNKNOWN, prefix + " 결과 불명",
                        slug + " · 체결 여부를 확정하지 못했습니다 — 보유 목록에서 확인하세요");
            } else if ("error".equals(verdict.kind) || "rejected".equals(verdict.kind)) {
                errorNotify(NotifyPolicy.KEY_ORDER_FAILED, prefix + " 실패",
                        slug + " · " + slice(verdict.error != null ? verdict.error : "주문 거부", 120));
            }
            String msgText = verdict.error != null
                    ? verdict.error
                    : "미체결 (status: " + (verdict.status.isEmpty() ? "없음" : verdict.status) + ")";
            logger.log(prefix + " 실패 " + slug + ": " + slice(msgText, 120)
                    + " · 응답 원문: " + slice(ModelJson.stringify(resp), 200));
            return false;
        }
        StopLoss.FillMath fill = StopLoss.computeFill(resp, shares, current, entry);
        // JS new Date().toISOString() — 밀리초 3자리 고정 UTC 표기
        String tsIso = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(clock.nowMs()));
        Map<String, Object> rec = StopLossLedger.record(
                slug,
                Js.field(row, "condition_id") == Js.UNDEFINED ? null : Js.field(row, "condition_id"),
                tokenId,
                Js.field(row, "outcome") == Js.UNDEFINED ? null : Js.field(row, "outcome"),
                entry,
                StopLoss.round4(fill.exitPrice),
                fill.soldShares,
                fill.proceeds,
                fill.realized,
                tsIso);
        if (manual) {
            rec.put("kind", "manual");
            manualRecorder.record(rec);
        } else {
            StopLossLedger.recordStopLoss(store, rec);
        }
        logger.log(prefix + " " + slug + " · 진입 " + JsNumber.toString(entry)
                + " → 매도 " + JsNumber.toString(StopLoss.round4(fill.exitPrice))
                + " · 회수 $" + JsNumber.toString(fill.proceeds)
                + " · 손익 " + (fill.realized < 0 ? "-" : "+") + "$"
                + JsNumber.toString(Math.abs(fill.realized)));
        if (notifier != null) {
            // 원장 기록 뒤의 알림이다. 예외가 새어 나가면 호출자가 체결을
            // 실패로 뒤집으므로 여기서 삼킨다.
            try {
                notifier.notifyMoney(manual ? "수동 매도 체결" : "손절 체결",
                        slug + " · " + JsNumber.toString(fill.soldShares) + "주"
                                + " · 체결가 " + JsNumber.toString(StopLoss.round4(fill.exitPrice))
                                + " · 실현손익 " + StopLoss.signedUsd(fill.realized));
            } catch (Exception ignored) {
                // 알림 실패는 체결 판정에 영향을 주지 않는다.
            }
        }
        return true;
    }

    /**
     * 결과 불명 시도 기록 — 손절은 손절 원장, 수동 매도는 별도 장부로 나간다.
     * 손절은 토큰과 함께 slug도 남긴다: 실제로는 체결됐을 수 있는데
     * bm_bought에 없는 포지션이면 체결 기록(stopLossSlugs)도, 보유
     * (heldSlugs)도 사라져 재매수 차단이 뚫리기 때문이다 (v0.3.35 감사 축 2).
     * 수동 매도는 사용자가 다시 팔 수 있어야 하므로 slug 차단을 걸지 않는다.
     */
    private void recordAttempt(String tokenId, String slug, boolean manual) {
        if (manual) {
            manualRecorder.recordAttempt(tokenId);
        } else {
            StopLossLedger.recordAttempt(store, tokenId);
            StopLossLedger.recordAttemptSlug(store, slug);
        }
    }

    private static String msg(Exception e) {
        String m = e.getMessage();
        return m != null ? m : e.toString();
    }

    /** JS String.slice(0, n) — 서로게이트 경계는 실무상 무시한다. */
    private static String slice(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n);
    }
}
