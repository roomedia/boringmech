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

    public interface Clock {
        long nowMs();
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

    public StopLossSeller(ClobWriteClient client, BalanceSource balanceSource,
                          OrderBookSource orderBookSource, SubmitGuard guard,
                          StopLossLedger.Store store, Logger logger,
                          MoneyNotifier notifier, ClobWriteClient.TimeoutRunner timeout,
                          Clock clock) {
        this.client = client;
        this.balanceSource = balanceSource;
        this.orderBookSource = orderBookSource;
        this.guard = guard;
        this.store = store;
        this.logger = logger;
        this.notifier = notifier;
        this.timeout = timeout;
        this.clock = clock;
    }

    /** 현재 세대 기준으로 실행하는 위임형 — 단독 사용·골든 테스트용. */
    public boolean executeStopLossSell(Map<String, Object> row, double current) {
        return executeStopLossSell(row, current, guard.currentGeneration());
    }

    /** executeStopLossSell — row는 Data API 포지션 행 모델(Map). gen은 호출 세션의 세대. */
    public boolean executeStopLossSell(Map<String, Object> row, double current, int gen) {
        String slug = Js.str(Js.field(row, "slug"));
        if (!guard.tryAcquireSubmitLock(slug, gen)) {
            logger.log("손절 제출 잠금 중 — 건너뜀 " + slug);
            return false;
        }
        try {
            if (!guard.beginSubmit(gen)) {
                logger.log("손절 제출 중단 — 세션 세대 교체 " + slug);
                return false;
            }
            try {
                return executeLocked(row, current);
            } catch (Exception e) {
                // 원본은 runStopLoss가 행 단위로 잡아 로그한다 — 여기서는
                // 같은 메시지로 남기고 false로 돌려준다.
                logger.log("손절 처리 오류 " + slug + ": " + slice(msg(e), 120));
                return false;
            } finally {
                guard.endSubmit();
            }
        } finally {
            guard.releaseSubmitLock(slug);
        }
    }

    private boolean executeLocked(Map<String, Object> row, double current) throws Exception {
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
            logger.log("손절 건너뜀 " + slug + " — 매도 가능 수량 부족 (" + JsNumber.toString(shares) + ")");
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
            logger.log("손절 매도 서명 실패 " + slug + ": " + slice(msg(e), 120));
            return false;
        }
        Object resp;
        try {
            resp = client.postOrder(signed, "FOK");
        } catch (Exception e) {
            StopLossLedger.recordAttempt(store, tokenId);
            logger.log("손절 매도 결과 불명 " + slug + ": " + slice(msg(e), 120)
                    + " — 다음 사이클에 재확인");
            return false;
        }
        JudgeFill.Verdict verdict = JudgeFill.judgeFill(resp);
        if (!verdict.filled) {
            if ("unknown".equals(verdict.kind)) {
                StopLossLedger.recordAttempt(store, tokenId);
            }
            String msgText = verdict.error != null
                    ? verdict.error
                    : "미체결 (status: " + (verdict.status.isEmpty() ? "없음" : verdict.status) + ")";
            logger.log("손절 매도 실패 " + slug + ": " + slice(msgText, 120)
                    + " · 응답 원문: " + slice(ModelJson.stringify(resp), 200));
            return false;
        }
        StopLoss.FillMath fill = StopLoss.computeFill(resp, shares, current, entry);
        // JS new Date().toISOString() — 밀리초 3자리 고정 UTC 표기
        String tsIso = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.ofEpochMilli(clock.nowMs()));
        StopLossLedger.recordStopLoss(store, StopLossLedger.record(
                slug,
                Js.field(row, "condition_id") == Js.UNDEFINED ? null : Js.field(row, "condition_id"),
                tokenId,
                Js.field(row, "outcome") == Js.UNDEFINED ? null : Js.field(row, "outcome"),
                entry,
                StopLoss.round4(fill.exitPrice),
                fill.soldShares,
                fill.proceeds,
                fill.realized,
                tsIso));
        logger.log("손절 매도 " + slug + " · 진입 " + JsNumber.toString(entry)
                + " → 매도 " + JsNumber.toString(StopLoss.round4(fill.exitPrice))
                + " · 회수 $" + JsNumber.toString(fill.proceeds)
                + " · 손익 " + (fill.realized < 0 ? "-" : "+") + "$"
                + JsNumber.toString(Math.abs(fill.realized)));
        if (notifier != null) {
            notifier.notifyMoney("손절 실행",
                    "진입 " + JsNumber.toString(entry)
                            + " · 청산 " + JsNumber.toString(StopLoss.round4(fill.exitPrice))
                            + " · 실현 " + StopLoss.signedUsd(fill.realized));
        }
        return true;
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
