// 네이티브 이관 2단계 골든 생성기 — summarizeBook 기준 출력 산출.
//
// 방식: 복붙이 아니라 app/src/engine.js 원본 텍스트에서 필요한 함수·상수를
// 중괄호 매칭으로 그대로 추출해 실행한다. 원본이 바뀌면 이 스크립트를 다시
// 돌리는 것만으로 기대 출력이 원본 기준으로 재생성된다.
// 추출 대상 (전부 summarizeBook과 snapshot() 사이징의 의존성):
//   상수 BET_PCT, BET_MIN_USD, CASH_FLOOR_PCT, RESERVE_USD, MAX_POSITIONS
//   round2, betUsdFor, sizing, positionKey, judgeClosedRow, realizedOf, summarizeBook
// 추출 후 원본 대조: 추출된 각 조각이 원본 파일에 verbatim으로 포함되는지
// indexOf로 검증한다 (추출 자체가 부분 문자열 절단이라 항상 참이지만,
// 마커를 못 찾으면 즉시 실패시켜 원본 구조 변경을 놓치지 않게 한다).
//
// 실행: node gen_expected.mjs  → fixtures.json을 읽어 expected.json을 쓴다.
// 결정성: summarizeBook·sizing은 시각·난수에 의존하지 않는다.
// heldSlugs·stopLossSlugs(Set)는 정렬된 배열로 직렬화한다.

import { readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const src = readFileSync(join(here, "../app/src/engine.js"), "utf8");

function extractBlock(marker) {
  const i = src.indexOf(marker);
  if (i < 0) throw new Error(`마커를 찾지 못함: ${marker}`);
  const open = src.indexOf("{", i);
  let depth = 0;
  for (let j = open; j < src.length; j += 1) {
    if (src[j] === "{") depth += 1;
    else if (src[j] === "}") {
      depth -= 1;
      if (depth === 0) return src.slice(i, j + 1);
    }
  }
  throw new Error(`중괄호 매칭 실패: ${marker}`);
}

function extractLine(marker) {
  const i = src.indexOf(marker);
  if (i < 0) throw new Error(`마커를 찾지 못함: ${marker}`);
  const end = src.indexOf("\n", i);
  return src.slice(i, end).replace(/;\s*$/, "");
}

const parts = [
  ...["BET_PCT", "BET_MIN_USD", "CASH_FLOOR_PCT", "RESERVE_USD", "MAX_POSITIONS"].map(
    (n) => extractLine(`const ${n} =`),
  ),
  extractLine("const round2 ="),
  extractBlock("export function betUsdFor"),
  extractBlock("export function sizing"),
  extractBlock("function positionKey"),
  extractBlock("export function judgeClosedRow"),
  extractBlock("function realizedOf"),
  extractBlock("export function summarizeBook"),
];
for (const p of parts) {
  if (!src.includes(p)) throw new Error("추출 조각이 원본에 verbatim으로 없음");
}
const code = parts.join("\n").replaceAll("export function", "function");
const { summarizeBook, sizing } = new Function(
  `${code}\nreturn { summarizeBook, sizing, round2 };`,
)();

const fixtures = JSON.parse(readFileSync(join(here, "fixtures.json"), "utf8"));
const round2 = (x) => Math.round((x + Number.EPSILON) * 100) / 100;

const out = { meta: { ...fixtures.meta, expected_from: "app/src/engine.js (추출 실행)" }, cases: {} };
for (const c of fixtures.cases) {
  const inp = c.input;
  const book = summarizeBook(
    inp.allRows,
    inp.winRows,
    inp.lostRows,
    inp.closedRows,
    inp.stopLossRecords,
    new Set(inp.stopLossAttempted || []),
  );
  let sizingOut = null;
  if (typeof inp.cash === "number" && typeof inp.positionsValue === "number") {
    const equity = round2(inp.cash + inp.positionsValue);
    sizingOut = { equity, ...sizing(inp.cash, equity, book.openCount) };
  }
  out.cases[c.name] = {
    openCount: book.openCount,
    positionsPnlPct: book.positionsPnlPct,
    settledCount: book.settledCount,
    settledWins: book.settledWins,
    settledLosses: book.settledLosses,
    settledRealizedPnl: book.settledRealizedPnl,
    settledRealizedExcluded: book.settledRealizedExcluded,
    settledStopLoss: book.settledStopLoss,
    heldSlugs: [...book.heldSlugs].sort(),
    stopLossSlugs: [...book.stopLossSlugs].sort(),
    sizing: sizingOut,
  };
}
writeFileSync(join(here, "expected.json"), JSON.stringify(out, null, 1) + "\n");
console.log(`expected.json 생성: ${Object.keys(out.cases).length} 케이스`);
