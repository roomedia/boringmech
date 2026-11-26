// 설정 잠금 모드 가드 (카드별, v0.3.46) — 평소 읽기 모드에서는 설정
// 컨트롤이 레이아웃 그대로 존재하되 disabled이고, 잠금은 카드별로
// 독립이다: 카드의 편집 아이콘은 그 카드만 열고, 다른 카드는 잠긴
// 상태를 유지하며, 그 카드의 저장·되돌리기 뒤에는 그 카드만 다시
// 잠긴다. 출하 HTML의 인라인 스크립트를 최소 fake DOM 위에서 실제로
// 실행해 검증한다 (jsdom 없음).
//
// 잠금을 disabled가 아니라 요소 숨김·교체로 구현하면, fake DOM에는
// 숨김 개념이 없어도 "같은 요소가 활성화된다"는 대조가 깨져 잡힌다.
import { readFileSync } from "node:fs";
import vm from "node:vm";

// 설정 표시가 엔진 정본 파서를 거치므로, 스텁 BM에도 실제 파서를
// 넣어 출하 경로와 같은 해석으로 검증한다.
globalThis.window = {};
const { parseTradeSettings, parseTempSettings } = await import("../src/engine.js");

const html = readFileSync(new URL("../index.html", import.meta.url), "utf8");

let passed = 0;
let failed = 0;
function check(cond, label) {
  if (cond) passed++;
  else { failed++; console.error(`FAIL ${label}`); }
}

// ── 출하 마크업에서 설정 영역 컨트롤을 그대로 뽑는다 ──────────────
const areaStart = html.indexOf('id="settingsArea"');
const areaEnd = html.indexOf("최근 로그", areaStart);
check(areaStart >= 0 && areaEnd > areaStart, "lock: settingsArea 영역이 마크업에 존재");
const areaHtml = html.slice(areaStart, areaEnd);

function parseTags(src, tag) {
  const out = [];
  const re = new RegExp(`<${tag}\\b[^>]*>`, "g");
  let m;
  while ((m = re.exec(src))) {
    const tagText = m[0];
    const id = /id="([^"]+)"/.exec(tagText)?.[1] || null;
    const cls = /class="([^"]+)"/.exec(tagText)?.[1] || "";
    out.push({
      id,
      cls,
      dataFor: /data-for="([^"]+)"/.exec(tagText)?.[1] || null,
      dataDir: /data-dir="([^"]+)"/.exec(tagText)?.[1] || null,
    });
  }
  return out;
}
const areaInputs = parseTags(areaHtml, "input").filter((t) => t.id);
const areaButtons = parseTags(areaHtml, "button").filter((t) => t.id);
const allInputs = parseTags(html, "input");
const sliders = allInputs.filter((t) => t.cls.split(/\s+/).includes("slider"));
const numins = allInputs.filter((t) => t.cls.split(/\s+/).includes("numin"));
const stepBtns = parseTags(html, "button").filter((t) => t.cls.split(/\s+/).includes("step-btn"));

// ── 최소 fake DOM ────────────────────────────────────────────────
class FakeEl {
  constructor(id) {
    this.id = id || "";
    this._classes = new Set();
    this.style = {};
    this.textContent = "";
    this.innerHTML = "";
    this.value = "";
    this.checked = false;
    this.disabled = false;
    this._listeners = {};
    this._attrs = {};
    this.children = [];
  }
  get className() { return [...this._classes].join(" "); }
  set className(v) { this._classes = new Set(String(v).split(/\s+/).filter(Boolean)); }
  get classList() {
    const s = this._classes;
    return {
      add: (...cs) => cs.forEach((c) => s.add(c)),
      remove: (...cs) => cs.forEach((c) => s.delete(c)),
      toggle: (c, force) => {
        const on = force === undefined ? !s.has(c) : !!force;
        if (on) s.add(c); else s.delete(c);
        return on;
      },
      contains: (c) => s.has(c),
    };
  }
  addEventListener(type, fn) { (this._listeners[type] ||= []).push(fn); }
  click() {
    const ev = { target: this, stopPropagation() {}, preventDefault() {} };
    (this._listeners.click || []).slice().forEach((fn) => fn(ev));
  }
  getAttribute(name) { return this._attrs[name] ?? null; }
  setAttribute(name, v) { this._attrs[name] = String(v); }
  closest() { return null; }
  querySelectorAll() { return []; }
  appendChild(child) { this.children.push(child); return child; }
  removeChild(child) { this.children = this.children.filter((c) => c !== child); }
  focus() {}
  select() {}
}

const registry = new Map();
function el(id) {
  if (!registry.has(id)) registry.set(id, new FakeEl(id));
  return registry.get(id);
}

// 설정 영역 컨트롤은 getElementById와 querySelectorAll이 같은 객체를
// 돌려줘야 "같은 요소" 대조가 성립한다.
for (const t of [...areaInputs, ...areaButtons]) {
  const e = el(t.id);
  e.className = t.cls;
  if (t.dataFor) e.setAttribute("data-for", t.dataFor);
  if (t.dataDir) e.setAttribute("data-dir", t.dataDir);
}
// 스테퍼 버튼은 id가 없어 data-for로 구분한다 — 카드별 잠금이 자기
// 카드의 스테퍼만 골라 disabled를 거는지 대조하는 데 쓴다.
const stepBtnEls = stepBtns.map((t) => {
  const e = new FakeEl("");
  e.className = t.cls;
  if (t.dataFor) e.setAttribute("data-for", t.dataFor);
  if (t.dataDir) e.setAttribute("data-dir", t.dataDir);
  return e;
});
const stepBtnFor = (inputId) => stepBtnEls.find((e) => e.getAttribute("data-for") === inputId);
el("numOverlay").classList.add("hidden");
el("confirmOverlay").classList.add("hidden");

const byIdFromParse = (list) => list.filter((t) => t.id).map((t) => el(t.id));
const documentStub = {
  getElementById: (id) => el(id),
  querySelectorAll: (sel) => {
    if (sel === "input.slider") return byIdFromParse(sliders);
    if (sel === ".step-btn") return stepBtnEls.slice();
    if (sel === ".numin") return byIdFromParse(numins);
    return [];
  },
  createElement: () => new FakeEl(""),
  createRange: () => ({ selectNodeContents() {} }),
  body: new FakeEl("body"),
  addEventListener() {},
};

const lsMap = new Map();
const sandbox = {
  console,
  setTimeout,
  clearTimeout,
  setInterval: () => 0,
  clearInterval: () => {},
  fetch: () => Promise.reject(new Error("no network in test")),
  AbortController,
  navigator: {},
  addEventListener: () => {},
  removeEventListener: () => {},
  document: documentStub,
  localStorage: {
    getItem: (k) => (lsMap.has(k) ? lsMap.get(k) : null),
    setItem: (k, v) => lsMap.set(k, String(v)),
    removeItem: (k) => lsMap.delete(k),
  },
  BM: {
    hostMode: () => "viewer",
    boot: () => ({ eoa: null, funder: null, view: "link" }),
    version: "test",
    eoa: null,
    funder: null,
    parseSnapCache: () => null,
    readSnapCache: () => null,
    compareVersions: () => 0,
    parseTradeSettings,
    parseTempSettings,
    settingsSummaryText: () => "",
    effectiveSettingsSummary: () => null,
    hasRelayerKey: () => false,
  },
};
sandbox.window = sandbox;
sandbox.globalThis = sandbox;
vm.createContext(sandbox);

const inline = /<script>([\s\S]*?)<\/script>/.exec(html)[1];
vm.runInContext(inline, sandbox, { filename: "index-inline.js" });

const $ = (id) => registry.get(id);
const bandSlider = $("ts_bandLo");
const sizeSlider = $("ts_cashFloorPct");
const stopSlider = $("ts_stopDrop");
check(!!bandSlider && !!sizeSlider && !!stopSlider, "lock: 카드별 슬라이더 요소가 존재한다");

// 1) 초기 상태는 전 카드 잠금 — 요소는 그대로 있고 disabled다.
check(["temp", "band", "size", "stop"].every((g) => sandbox.cardLocked[g] === true),
  "lock: 초기 상태는 전 카드 잠금이다");
check(bandSlider.disabled === true, "lock: 잠금 상태에서 매수 카드 슬라이더가 disabled다");
check($("tempNoEnabled").disabled === true, "lock: 잠금 상태에서 기온 스위치가 disabled다");
check(sizeSlider.disabled === true, "lock: 잠금 상태에서 사이징 슬라이더가 disabled다");
check(stopSlider.disabled === true, "lock: 잠금 상태에서 손절 슬라이더가 disabled다");
check(stepBtnFor("ts_betUsd").disabled === true, "lock: 잠금 상태에서 사이징 스테퍼가 disabled다");
for (const b of ["btnEditTemp", "btnEditBand", "btnEditSize", "btnEditStop"]) {
  check($(b).disabled === false, `lock: ${b} 편집 아이콘은 잠금 상태에서도 눌린다`);
}

// 2) 매수 카드만 열기 — 그 카드만 활성화되고 다른 카드는 잠긴 채다.
//    변경이 없으면 저장 버튼은 그대로 비활성이다 (전부 켜는 구현 방지).
$("btnEditBand").click();
check(sandbox.cardLocked.band === false, "lock: 매수 카드 편집 아이콘으로 그 카드만 해제된다");
check(sandbox.cardLocked.temp === true && sandbox.cardLocked.size === true && sandbox.cardLocked.stop === true,
  "lock: 다른 카드는 잠긴 상태를 유지한다");
check(bandSlider.disabled === false, "lock: 해제 시 같은 슬라이더 요소가 활성화된다");
check($("tempNoEnabled").disabled === true, "lock: 기온 카드는 여전히 disabled다");
check(sizeSlider.disabled === true, "lock: 사이징 카드는 여전히 disabled다");
check(stopSlider.disabled === true, "lock: 손절 카드는 여전히 disabled다");
check(stepBtnFor("ts_endWindowDays").disabled === false, "lock: 열린 카드의 스테퍼는 활성화된다");
check(stepBtnFor("ts_betUsd").disabled === true, "lock: 잠긴 카드의 스테퍼는 여전히 disabled다");
check($("btnBandSave").disabled === true, "lock: 해제 직후 변경 없으면 저장 버튼은 비활성이다");

// 3) 매수 카드 값을 바꾸면 저장 버튼이 켜지고, 저장하면 그 카드만
//    다시 잠긴다.
sandbox.renderTradeSettings();
for (const g of Object.values(sandbox.TRADE_GROUPS)) {
  for (const f of g.fields) $(f.id).value = String(f.def);
}
bandSlider.value = "0.65";
sandbox.updateSettingsUI();
check($("btnBandSave").disabled === false, "lock: 변경 후 저장 버튼이 활성화된다");
$("btnBandSave").click();
check(sandbox.cardLocked.band === true, "lock: 저장하면 그 카드만 다시 잠긴다");
check(bandSlider.disabled === true, "lock: 저장 후 슬라이더가 다시 disabled다");
check(sandbox.cardLocked.temp === true && sandbox.cardLocked.size === true && sandbox.cardLocked.stop === true,
  "lock: 저장해도 다른 카드 상태는 그대로다");
check(JSON.parse(lsMap.get("bm_settings")).bandLo === 0.65, "lock: 저장값이 실제로 기록됐다");

// 4) 기온 카드 되돌리기(저장본으로) — 확인 팝업 뒤 그 카드만 잠긴다.
sandbox.renderTempSettings();
$("btnEditTemp").click();
check(sandbox.cardLocked.temp === false, "lock: 기온 카드만 열린다");
check($("tempNoEnabled").disabled === false, "lock: 기온 스위치가 활성화된다");
check(bandSlider.disabled === true, "lock: 매수 카드는 여전히 잠겨 있다");
$("tempNoBandLo").value = "0.85";
sandbox.updateSettingsUI();
check($("btnTempSaved").disabled === false, "lock: 변경 후 되돌리기 버튼이 활성화된다");
$("btnTempSaved").click();
check(!$("confirmOverlay").classList.contains("hidden"), "lock: 되돌리기가 확인 팝업을 연다");
$("btnConfirmOk").click();
check(sandbox.cardLocked.temp === true, "lock: 되돌리기 후 그 카드만 다시 잠긴다");
check($("tempNoEnabled").disabled === true, "lock: 되돌리기 후 기온 스위치가 다시 disabled다");
check(Number($("tempNoBandLo").value) === 0.8, "lock: 되돌리기로 폼이 저장본으로 돌아갔다");

// 5) 카드 여럿이 동시에 열려 있어도 된다 — 각자 독립으로 잠긴다.
$("btnEditBand").click();
$("btnEditSize").click();
check(bandSlider.disabled === false && sizeSlider.disabled === false,
  "lock: 두 카드가 동시에 열릴 수 있다");
check(stopSlider.disabled === true, "lock: 열지 않은 손절 카드는 잠긴 채다");
$("btnEditBand").click();
check(sandbox.cardLocked.band === true && bandSlider.disabled === true,
  "lock: 편집 아이콘 재클릭으로 그 카드만 다시 잠긴다");
check(sandbox.cardLocked.size === false && sizeSlider.disabled === false,
  "lock: 다른 열린 카드는 영향을 받지 않는다");
$("btnEditSize").click();
check(sandbox.cardLocked.size === true && sizeSlider.disabled === true, "lock: 사이징 카드도 독립으로 잠긴다");

// 6) 숫자 입력 시트는 그 카드가 잠겨 있으면 열리지 않고, 열린 카드의
//    값만 열린다.
sandbox.openNumSheet({ inputId: "ts_volumeMin", dispId: null, label: "최소 거래량" });
check($("numOverlay").classList.contains("hidden"), "lock: 잠긴 카드에서는 숫자 입력 시트가 열리지 않는다");
$("btnEditBand").click();
sandbox.openNumSheet({ inputId: "ts_volumeMin", dispId: null, label: "최소 거래량" });
check(!$("numOverlay").classList.contains("hidden"), "lock: 열린 카드에서는 숫자 입력 시트가 열린다");
sandbox.closeNumSheet();
sandbox.openNumSheet({ inputId: "tempNoBandLo", dispId: "val_tempBandLo", label: "밴드 하한" });
check($("numOverlay").classList.contains("hidden"),
  "lock: 다른 카드가 열려 있어도 잠긴 기온 카드의 시트는 열리지 않는다");
$("btnEditBand").click();

// 7) 기온 카드 저장 — 건당 금액(tempBetUsd)까지 저장본에 남아야 한다.
//    저장 핸들러의 필드 목록에서 하나가 빠지면 저장 직후 폼이 기본값으로
//    되돌아가 "올려도 계속 $5"가 되던 결함의 가드.
sandbox.renderTempSettings();
$("btnEditTemp").click();
check(sandbox.cardLocked.temp === false, "lock: 기온 카드가 열린다 (건당 금액 저장 검증)");
$("tempBetUsd").value = "10";
sandbox.updateSettingsUI();
check($("btnTempSave").disabled === false, "lock: 기온 건당 금액 변경 후 저장 버튼이 활성화된다");
$("btnTempSave").click();
check(JSON.parse(lsMap.get("bm_settings")).tempBetUsd === 10,
  "lock: 기온 건당 금액이 저장본(bm_settings)에 기록됐다");
check(Number($("tempBetUsd").value) === 10,
  "lock: 저장 후에도 폼의 기온 건당 금액이 올린 값으로 유지된다");
check($("tempNoErr").textContent === "", "lock: 기온 저장 시 오류 표시가 없다");
check(sandbox.cardLocked.temp === true, "lock: 기온 저장 후 그 카드만 다시 잠긴다");

// 8) 매매 그룹 저장 확인 — 저장소 쓰기가 실패하면(예외를 삼키는
//    writeSettingsObject 아래) 조용히 적용된 척하지 않고 오류를 표시하고
//    카드를 잠그지 않아야 한다. 기온 카드에만 있던 재읽기 대조를 매매
//    그룹 저장에도 걸었는지의 가드.
if (sandbox.cardLocked.band) $("btnEditBand").click();
check(sandbox.cardLocked.band === false, "lock: 매수·검증 카드가 열린다 (저장 확인 검증)");
$("ts_bandLo").value = "0.62";
sandbox.updateSettingsUI();
check($("btnBandSave").disabled === false, "lock: 밴드 변경 후 저장 버튼이 활성화된다");
const origSetItem = sandbox.localStorage.setItem;
sandbox.localStorage.setItem = () => { throw new Error("quota"); };
$("btnBandSave").click();
check($("bandErr").textContent.includes("저장이 확인되지 않았습니다"),
  "lock: 쓰기 실패 시 매매 그룹 저장도 오류를 표시한다");
check(sandbox.cardLocked.band === false, "lock: 쓰기 실패 시 카드를 잠그지 않는다");
check(JSON.parse(lsMap.get("bm_settings")).bandLo === 0.65,
  "lock: 쓰기 실패 시 저장본은 이전 값 그대로다");
sandbox.localStorage.setItem = origSetItem;
$("btnBandSave").click();
check($("bandErr").textContent === "", "lock: 재시도 저장 시 오류 표시가 없다");
check(JSON.parse(lsMap.get("bm_settings")).bandLo === 0.62,
  "lock: 재시도 저장이 저장본에 기록됐다");
check(sandbox.cardLocked.band === true, "lock: 재시도 저장 후 카드가 다시 잠긴다");

console.log(`settings-lock tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
