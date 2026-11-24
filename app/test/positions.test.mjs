// 보유 종목 접힌 요약 가드 (v0.3.47) — 접힌 상태에서 보이는 요약
// 수치는 summarizePositions가 정본이다: 그룹 손익률은 그룹 손익 합 ÷
// 그룹 진입 원금 합, 본전(손익 0)은 수익·손실 어느 쪽에도 세지 않고,
// 손익을 계산할 수 없는 행은 건수·가치에만 들어간다. 출하 HTML에서
// 함수 본문을 그대로 뽑아 실행해 검증한다.
import { readFileSync } from "node:fs";
import vm from "node:vm";

const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");

let passed = 0;
let failed = 0;
function check(cond, label) {
  if (cond) passed++;
  else { failed++; console.error(`FAIL ${label}`); }
}
function approx(a, b) { return a != null && Math.abs(a - b) < 1e-9; }

function extractFn(name) {
  const start = html.indexOf(`function ${name}(`);
  check(start >= 0, `positions: ${name} 정의 존재`);
  const end = html.indexOf("\n    }", start);
  check(end > start, `positions: ${name} 본문 경계 확인`);
  return html.slice(start, end + 6);
}

const ctx = {};
vm.createContext(ctx);
vm.runInContext(
  extractFn("summarizePositions") + "\nthis.__sum = summarizePositions;",
  ctx,
);
const summarizePositions = ctx.__sum;
check(typeof summarizePositions === "function", "positions: summarizePositions 실행 가능");

// 혼합 케이스 — 수익 2건, 손실 1건, 본전 1건, 손익 불명 1건
{
  const s = summarizePositions([
    { current_size: 200, avg_price: 0.5, current_value: 120 }, // +20 (원금 100)
    { current_size: 100, avg_price: 0.5, current_value: 55 },  // +5  (원금 50)
    { current_size: 100, avg_price: 0.8, current_value: 60 },  // -20 (원금 80)
    { current_size: 60, avg_price: 0.5, current_value: 30 },   // 0 (본전)
    { current_size: 10, avg_price: 0.5, current_value: null }, // 손익 불명
  ]);
  check(s.count === 5, "positions: 보유 건수는 전 행을 센다");
  check(approx(s.value, 265), "positions: 포지션 가치는 평가액 합이다");
  check(approx(s.pnl, 5), "positions: 전체 손익은 계산 가능한 행만의 합이다");
  check(s.winCount === 2 && approx(s.winPnl, 25) && approx(s.winCost, 150),
    "positions: 수익 그룹 집계");
  check(approx(s.winPct, 25 / 150 * 100), "positions: 수익률은 그룹 손익 ÷ 그룹 원금이다");
  check(s.lossCount === 1 && approx(s.lossPnl, -20) && approx(s.lossCost, 80),
    "positions: 손실 그룹 집계 (본전은 어느 쪽에도 안 들어간다)");
  check(approx(s.lossPct, -25), "positions: 손실률은 그룹 손익 ÷ 그룹 원금이다");
}

// 빈 입력과 전부 손실인 경우 — 반대쪽 손익률은 null로 떨어진다
{
  const empty = summarizePositions(null);
  check(empty.count === 0 && empty.winPct === null && empty.lossPct === null,
    "positions: 빈 입력은 0건과 null 손익률이다");
  const onlyLoss = summarizePositions([
    { current_size: 100, avg_price: 0.9, current_value: 45 },
  ]);
  check(onlyLoss.winCount === 0 && onlyLoss.winPct === null,
    "positions: 수익 종목이 없으면 수익률은 null이다");
  check(onlyLoss.lossCount === 1 && approx(onlyLoss.lossPct, -50),
    "positions: 전부 손실이면 손실률만 계산된다");
}

// 문자열 숫자로 와도 같은 결과다 (스냅샷 정규화 전 행 방어)
{
  const s = summarizePositions([
    { current_size: "100", avg_price: "0.5", current_value: "60" },
  ]);
  check(s.winCount === 1 && approx(s.winPct, 20), "positions: 문자열 숫자도 계산된다");
}

console.log(`positions tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
