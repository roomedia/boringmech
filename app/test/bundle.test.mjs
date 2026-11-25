// 출하 번들(app/engine.js) 스모크 — src만 고치고 번들을 재생성하지 않으면
// 실기기에서는 구 로직이 돈다 (v0.3.33 감사 발견). 번들을 실제로 평가해
// window.BM이 스냅샷 캐시의 positions를 보존하는지 확인한다.
import { readFileSync } from "node:fs";

globalThis.window = {};
const code = readFileSync(new URL("../engine.js", import.meta.url), "utf8");
new Function(code)();

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
  } else {
    failed++;
    console.error(`FAIL ${label}: expected ${e}, got ${a}`);
  }
}

const BM = globalThis.window.BM;
eq(typeof BM, "object", "bundle: window.BM exposed");
eq(typeof BM?.parseSnapCache, "function", "bundle: parseSnapCache exists");

if (BM && typeof BM.parseSnapCache === "function") {
  const parsed = BM.parseSnapCache(
    JSON.stringify({
      cash: 12.5,
      equity: 40.1,
      openCount: 1,
      positions: [
        {
          slug: "will-btc-hit-100k",
          token_id: "tok-yes-1",
          title: "Will BTC hit 100k?",
          outcome: "Yes",
          avg_price: 0.72,
          current_price: 0.81,
          current_size: 6.94,
          current_value: 5.62,
          fee_rate: 0.04,
        },
      ],
    }),
  );
  eq(parsed?.positions?.length, 1, "bundle: positions survive parse");
  eq(parsed?.positions?.[0]?.token_id, "tok-yes-1", "bundle: token_id survives");
  eq(parsed?.positions?.[0]?.slug, "will-btc-hit-100k", "bundle: slug survives");

  const legacy = BM.parseSnapCache('{"cash":10,"equity":10}');
  eq(Array.isArray(legacy?.positions) && legacy.positions.length === 0, true,
    "bundle: legacy cache yields empty positions");
}

// 하단 요약 — 출하 번들에도 해석·렌더 경로가 실려 있어야 한다.
eq(typeof BM?.settingsSummaryText, "function", "bundle: settingsSummaryText exists");
eq(typeof BM?.parseTradeSettings, "function", "bundle: parseTradeSettings exists");
if (BM && typeof BM.settingsSummaryText === "function" && typeof BM.parseTradeSettings === "function") {
  eq(BM.settingsSummaryText(BM.parseTradeSettings(null)),
    "밴드 0.60–0.90 · 건당 $5.00 · 현금 30% 유지 · 최대 70건 · 손절 진입가 −0.25, 그 외 만기 보유",
    "bundle: summary defaults match legacy text");
  eq(BM.settingsSummaryText(BM.parseTradeSettings({ maxPositions: 5 })),
    "밴드 0.60–0.90 · 건당 $5.00 · 현금 30% 유지 · 최대 5건 · 손절 진입가 −0.25, 그 외 만기 보유",
    "bundle: summary reflects changed settings");
}

console.log(`bundle tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
