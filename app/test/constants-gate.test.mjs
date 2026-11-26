// 삼중 구현 상수 대조 게이트 연결 테스트 (분리 5/7, 결합 5).
// 실제 대조는 tools/constants_gate.py가 한다: JS 정본·Java 미러·Python
// 트랙의 판단 상수 38규칙 100개 추출 지점을 대조하고 어긋나면 실패한다.
// 실행: node test/constants-gate.test.mjs
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";

let passed = 0;
let failed = 0;
function eq(actual, expected, label) {
  if (actual === expected) {
    passed += 1;
  } else {
    failed += 1;
    console.error(`FAIL ${label}\n  actual:   ${actual}\n  expected: ${expected}`);
  }
}

const gate = fileURLToPath(new URL("../../tools/constants_gate.py", import.meta.url));
let out = "";
let code = 0;
try {
  out = execFileSync("python3", [gate], { encoding: "utf8" });
} catch (e) {
  code = e.status ?? 1;
  out = `${e.stdout ?? ""}${e.stderr ?? ""}`;
}
console.log(out.trim());
eq(code, 0, "constants gate exits 0 (all implementations agree)");
eq(/constants gate: PASS/.test(out), true, "constants gate reports PASS");

console.log(`constants-gate tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
