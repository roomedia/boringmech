// 보유 종목 접힌 요약 가드 (v0.3.47, 단위 정정 v0.3.48) — 접힌
// 상태에서 보이는 요약 수치는 summarizePositions가 정본이다: 수익·
// 손실 종목 표기는 그룹 손익 합계(달러), 본전(손익 0)은 수익·손실
// 어느 쪽에도 세지 않고, 손익을 계산할 수 없는 행은 건수·가치에만
// 들어간다. 출하 HTML에서 함수 본문을 그대로 뽑아 실행해 검증한다.
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
  check(s.winCount === 2 && approx(s.winPnl, 25),
    "positions: 수익 그룹 집계는 건수와 달러 합계다");
  check(s.lossCount === 1 && approx(s.lossPnl, -20),
    "positions: 손실 그룹 집계는 건수와 달러 합계다 (본전은 어느 쪽에도 안 들어간다)");
  check(approx(s.winPnl + s.lossPnl, s.pnl),
    "positions: 수익·손실 그룹 달러 합은 전체 손익과 일치한다");
  check(s.winPct === undefined && s.lossPct === undefined,
    "positions: 그룹 손익률(%) 필드는 없다 — 표기 단위는 달러다");
}

// 빈 입력과 전부 손실인 경우 — 반대쪽 그룹은 0건·0달러다
{
  const empty = summarizePositions(null);
  check(empty.count === 0 && empty.winPnl === 0 && empty.lossPnl === 0,
    "positions: 빈 입력은 0건과 0달러다");
  const onlyLoss = summarizePositions([
    { current_size: 100, avg_price: 0.9, current_value: 45 },
  ]);
  check(onlyLoss.winCount === 0 && onlyLoss.winPnl === 0,
    "positions: 수익 종목이 없으면 수익 합계는 0달러다");
  check(onlyLoss.lossCount === 1 && approx(onlyLoss.lossPnl, -45) && approx(onlyLoss.pnl, -45),
    "positions: 전부 손실이면 손실 합계만 계산된다");
}

// 문자열 숫자로 와도 같은 결과다 (스냅샷 정규화 전 행 방어)
{
  const s = summarizePositions([
    { current_size: "100", avg_price: "0.5", current_value: "60" },
  ]);
  check(s.winCount === 1 && approx(s.winPnl, 10), "positions: 문자열 숫자도 계산된다");
}

console.log(`positions tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
