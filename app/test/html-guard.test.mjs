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

// 화면 정리 가드 (v0.3.45) — 읽기 순서는 평가 총액 → 실현 손익 →
// 포지션이고, 정산 요약은 실현 손익만 크게 보이며 건수·승패·손절은
// 펼침 안에만 있다. 요약 한 줄에 전부 합쳐 그리던 형태로 되돌아가면
// 여기서 잡는다.
const idxEquity = html.indexOf('id="equityBig"');
const idxSettledBig = html.indexOf('id="settledPnlBig"');
const idxPosList = html.indexOf('id="posList"');
check(idxEquity >= 0 && idxSettledBig > idxEquity && idxPosList > idxSettledBig,
  "html-guard: 읽기 순서가 평가 총액 → 실현 손익 → 포지션이다");
check(/id="settledDetail" class="hidden"/.test(html), "html-guard: 정산 상세는 기본 접힘이다");
check(/id="sizingDetail" class="hidden"/.test(html), "html-guard: 다음 사이징 상세는 기본 접힘이다");
const idxDetail = html.indexOf('id="settledDetail"');
const idxDetailBtn = html.indexOf('id="btnSettledDetail"');
for (const id of ["settledCount", "settledWL", "settledSL"]) {
  const i = html.indexOf(`id="${id}"`);
  check(i > idxDetail && i < idxDetailBtn, `html-guard: ${id}는 정산 상세 펼침 안에만 있다`);
}
check(!html.includes("settledCount + '건 · '"), "html-guard: 정산 요약 합본 문자열이 남아 있지 않다");
check(html.includes("expandedPos"), "html-guard: 포지션 펼침 상태 유지 코드가 있다");

// 설정 잠금 가드 (v0.3.45) — 잠금은 요소를 숨기거나 값 표시로 대체하지
// 않고 disabled로만 표현한다. 설정 컨트롤이 영역 밖에 생기거나,
// 재잠금 지점이 사라지면 여기서 잡는다 (동작 검증은 settings-lock).
check(html.includes('id="settingsArea"'), "html-guard: settingsArea 마크업 존재");
check(html.includes('id="btnSettingsEdit"'), "html-guard: 설정 편집 아이콘 존재");
check(html.includes("function setSettingsLocked("), "html-guard: setSettingsLocked 정의 존재");
check(html.includes("if (settingsLocked) return;"), "html-guard: 잠금 시 숫자 입력 시트 차단 존재");
check((html.match(/setSettingsLocked\(true\)/g) || []).length >= 3,
  "html-guard: 저장·되돌리기 재잠금 지점이 3곳 이상이다");
const areaStart = html.indexOf('id="settingsArea"');
const areaEnd = html.indexOf("최근 로그", areaStart);
const settingsIds = [...html.matchAll(/id="((?:ts_|temp)[A-Za-z]+)"/g)].map((m) => m[1]);
check(settingsIds.length >= 20, "html-guard: 설정 컨트롤 id를 찾았다");
for (const id of new Set(settingsIds)) {
  const i = html.indexOf(`id="${id}"`);
  check(i > areaStart && i < areaEnd, `html-guard: 설정 컨트롤 ${id}가 settingsArea 안에 있다`);
}

console.log(`html-guard tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
