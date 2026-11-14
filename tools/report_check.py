#!/usr/bin/env python3
"""boringmech 앱 오류 제보 수신 확인 — 릴레이 D1의 app_reports에서
워터마크 이후 제보를 가져오고 워터마크를 전진시킨다 (소비형).
새 제보가 있으면 JSON 배열을 stdout으로 출력하고, 없으면 []를 출력한다.
조회 실패 시 비정상 종료해 호출자(훅)가 워터마크를 건드리지 않게 한다.
"""
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
from dynamic_credentials import add_surrogate_to_request, read_json_response

ACCOUNT_ID = "80f690a6b3418501f6949dc6015ed970"
D1_ID = "53be6797-f6cb-4f08-a0f4-0f18143fbbe2"
STATE = Path(__file__).with_name("report_watch_state.json")


def d1_query(sql, params):
    url = (
        f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT_ID}"
        f"/d1/database/{D1_ID}/query"
    )
    body = json.dumps({"sql": sql, "params": params}).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("Content-Type", "application/json")
    add_surrogate_to_request(req, "custom.cloudflare", allowed_hosts=["api.cloudflare.com"])
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = read_json_response(resp)
    if not data.get("success"):
        raise RuntimeError(f"D1 query failed: {json.dumps(data)[:400]}")
    return data["result"][0]["results"]


def main():
    last = 0
    if STATE.exists():
        last = int(json.loads(STATE.read_text()).get("last_id", 0))
    rows = d1_query(
        "SELECT id, at, app, version, payload FROM app_reports "
        "WHERE id > ? ORDER BY id ASC LIMIT 10",
        [last],
    )
    if rows:
        STATE.write_text(json.dumps({"last_id": rows[-1]["id"]}))
    print(json.dumps(rows, ensure_ascii=False))


if __name__ == "__main__":
    main()
