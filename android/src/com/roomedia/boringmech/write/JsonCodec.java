package com.roomedia.boringmech.write;

/**
 * JSON 모델(Map/List/String/Double/Boolean/null) ↔ 문자열 변환 추상화.
 *
 * 쓰기 경로는 응답 본문을 모델로 파싱하고, 로그용으로 모델을 다시
 * JSON.stringify 해야 한다. 파싱은 실행 환경마다 다르다(Android는
 * org.json, JVM 테스트는 테스트 파서) — stringify는 ModelJson이 순수
 * 구현을 제공하므로, 구현체는 parse만 환경에 맞게 제공하면 된다.
 */
public interface JsonCodec {
    /** JSON 텍스트를 모델로 파싱한다. 실패하면 예외. */
    Object parse(String text) throws Exception;

    /** 모델을 JSON.stringify와 같은 문자열로 직렬화한다. */
    String stringify(Object model);
}
