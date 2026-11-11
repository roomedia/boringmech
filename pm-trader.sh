#!/bin/bash
# pm-trader 실행 래퍼. 일부 환경의 NO_PROXY/no_proxy에 들어있는 대괄호 IPv6
# 항목([::1] 등) 때문에 httpx가 URL 파싱 실패로 터지므로, localhost만 남기고
# 덮어쓴다. 프록시 자체(HTTP_PROXY 등)는 그대로 유지해야 외부 API 접근이 된다.
export NO_PROXY=localhost,127.0.0.1
export no_proxy=localhost,127.0.0.1

# pm-trader CLI 경로 (기본: PATH에서 탐색). 생산 환경에서는 polymarket-paper-trader
# 가상환경의 pm-trader 바이너리를 PM_TRADER_BIN으로 지정해 썼다.
PM_TRADER_BIN="${PM_TRADER_BIN:-pm-trader}"
# 계좌 데이터 디렉토리 (기본: 이 스크립트가 있는 디렉토리 — 계좌별 하위 폴더에
# paper.db가 생긴다). 생산 환경에서는 PM_TRADER_DATA_DIR로 분리해 썼다.
PM_TRADER_DATA_DIR="${PM_TRADER_DATA_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

exec "$PM_TRADER_BIN" --data-dir "$PM_TRADER_DATA_DIR" "$@"
