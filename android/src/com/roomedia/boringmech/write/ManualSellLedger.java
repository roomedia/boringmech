package com.roomedia.boringmech.write;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 수동 매도 장부(bm_manual_sell·bm_manual_sell_archive)와 수동 attempted
 * 장부(bm_manual_sell_attempted·bm_manual_sell_attempted_archive)의 단일
 * 소유자 (공리적 분리 6단계, 감사 결합 6).
 *
 * 손절 attempted는 StopLossLedger가, 수동 attempted는 이 클래스가
 * 소유하고, 실행부(StopLossSeller)는 두 장부 모두 고정 인터페이스로만
 * 쓴다 — 기록은 StopLossSeller.ManualSellRecorder의 record·
 * recordAttempt, 조회는 records·attemptedTokens. NativeCycle은 키를
 * 직접 읽고 쓰지 않고 이 소유자를 경유한다.
 *
 * 저장은 StopLossLedger.Store 뒤에 있다 — NativeCycle이 종전 인라인
 * 합성과 같은 env durable 경로로 어댑터를 붙인다 (실기기에서는 같은
 * bm_durable이 백엔드다). 목록 연산 자체는 ManualSell의 순수 함수를
 * 그대로 쓰고, read-modify-write 전체를 store 모니터로 감싼다
 * (StopLossLedger와 같은 규율). 쓰기 순서는 종전 합성과 동일하게
 * 최근 목록 먼저, 아카이브 다음이다. 읽기는 키가 없거나 배열이
 * 아니면 빈 목록으로 본다 (종전 loadDurableJsonList 의미론).
 */
public final class ManualSellLedger implements StopLossSeller.ManualSellRecorder {
    private final StopLossLedger.Store store;

    public ManualSellLedger(StopLossLedger.Store store) {
        this.store = store;
    }

    /** 키의 목록 모델 — 없거나 배열이 아니면 빈 목록 (복사본). */
    private List<Object> list(String key) {
        Object v = store.read(key);
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new ArrayList<>(l);
        }
        return new ArrayList<>();
    }

    /** 수동 매도 체결 기록 — 최근 캡을 넘기면 슬림 형태로 아카이브 회전. */
    @Override
    public void record(Map<String, Object> rec) {
        synchronized (store) {
            ManualSell.CappedLists r = ManualSell.appendRecordWithArchive(
                    list(ManualSell.ARCHIVE_KEY), list(ManualSell.LEDGER_KEY), rec);
            store.write(ManualSell.LEDGER_KEY, r.recent);
            store.write(ManualSell.ARCHIVE_KEY, r.archive);
        }
    }

    /** 결과 불명 수동 매도 시도 토큰 — 중복이면 무동작, 넘치면 아카이브 회전. */
    @Override
    public void recordAttempt(String tokenId) {
        synchronized (store) {
            ManualSell.CappedLists r = ManualSell.appendAttemptedWithArchive(
                    list(ManualSell.ATTEMPTED_ARCHIVE_KEY), list(ManualSell.ATTEMPTED_KEY),
                    tokenId);
            store.write(ManualSell.ATTEMPTED_KEY, r.recent);
            store.write(ManualSell.ATTEMPTED_ARCHIVE_KEY, r.archive);
        }
    }

    /** 장부 집계 입력 — 아카이브가 먼저, 최근 기록이 뒤 (ManualSell.combineRecords). */
    public List<Object> records() {
        synchronized (store) {
            return ManualSell.combineRecords(
                    list(ManualSell.ARCHIVE_KEY), list(ManualSell.LEDGER_KEY));
        }
    }

    /** 시도 토큰 합집합 — 아카이브와 최근을 합쳐 문자열만, 순서는 유지한다. */
    public List<Object> attemptedTokens() {
        synchronized (store) {
            return ManualSell.combineAttempted(
                    list(ManualSell.ATTEMPTED_ARCHIVE_KEY), list(ManualSell.ATTEMPTED_KEY));
        }
    }
}
