// 출하 HTML 가드 — 앱 WebView는 WebChromeClient를 설정하지 않아
// window.confirm·alert·prompt가 대화상자 없이 기본값(false·빈 값)을
// 반환하고, 그걸 게이트로 쓰는 핸들러는 조용히 죽는다 (v0.3.41 측정
// 시작 무반응 결함). 확인이 필요한 경로는 인페이지 팝업(askConfirm)을
// 써야 하며, 네이티브 대화상자 API 호출이 출하 HTML에 다시 들어오면
// 이 테스트가 막는다.
import { readFileSync } from "node:fs";

const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");

let passed = 0;
let failed = 0;
function check(cond, label) {
  if (cond) {
    passed++;
  } else {
    failed++;
    console.error(`FAIL ${label}`);
  }
}

const banned = [
  [/window\s*\.\s*confirm\s*\(/, "window.confirm() 호출 잔존"],
  [/window\s*\.\s*alert\s*\(/, "window.alert() 호출 잔존"],
  [/window\s*\.\s*prompt\s*\(/, "window.prompt() 호출 잔존"],
  [/(?<![\w.])confirm\s*\(/, "confirm() 직접 호출 잔존"],
  [/(?<![\w.])alert\s*\(/, "alert() 직접 호출 잔존"],
  [/(?<![\w.])prompt\s*\(/, "prompt() 직접 호출 잔존"],
];
for (const [re, label] of banned) {
  check(!re.test(html), `html-guard: ${label}`);
}

// 대체 경로(인페이지 확인 팝업)가 실제로 실려 있는지도 함께 본다 —
// 호출만 지우고 팝업이 없으면 확인 경로 자체가 사라진 것이다.
check(html.includes('id="confirmOverlay"'), "html-guard: confirmOverlay 마크업 존재");
check(html.includes("function askConfirm("), "html-guard: askConfirm 정의 존재");
check(html.includes('id="btnConfirmOk"'), "html-guard: btnConfirmOk 존재");

console.log(`html-guard tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
