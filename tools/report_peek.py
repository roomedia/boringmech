#!/usr/bin/env python3
"""제보 원문 조회 (비소비형) — 워터마크를 건드리지 않고 id 범위를 읽는다."""
import json
import sys

sys.path.insert(0, "/opt/muse/skills/custom-connector-setup/bin")
sys.path.insert(0, str(__file__).rsplit("/", 1)[0])
from report_check import d1_query

lo = int(sys.argv[1]) if len(sys.argv) > 1 else 5
hi = int(sys.argv[2]) if len(sys.argv) > 2 else 8
rows = d1_query(
    "SELECT id, at, app, version, payload FROM app_reports "
    "WHERE id >= ? AND id <= ? ORDER BY id ASC",
    [lo, hi],
)
for r in rows:
    p = json.loads(r["payload"]) if isinstance(r["payload"], str) else r["payload"]
    print(f"=== id {r['id']} at {r['at']} version {r['version']} ===")
    print("reason:", p.get("reason"), "| auto:", p.get("auto"))
    print("stage:", json.dumps(p.get("stage"), ensure_ascii=False))
    snap = p.get("snap") or {}
    print("snap:", json.dumps({k: snap.get(k) for k in ("cash", "equity", "openCount")}, ensure_ascii=False))
    logs = p.get("logs") or []
    print(f"logs ({len(logs)}):")
    for line in logs:
        print("  ", line)
