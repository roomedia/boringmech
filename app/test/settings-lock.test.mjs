// 설정 잠금 모드 가드 — 평소 읽기 모드에서는 설정 컨트롤이 레이아웃
// 그대로 존재하되 disabled이고, 편집 아이콘을 눌렀을 때만 같은 요소가
// 활성화되며, 저장·되돌리기 뒤에는 다시 잠긴다. 출하 HTML의 인라인
// 스크립트를 최소 fake DOM 위에서 실제로 실행해 검증한다 (jsdom 없음).
//
// 잠금을 disabled가 아니라 요소 숨김·교체로 구현하면, fake DOM에는
// 숨김 개념이 없어도 "같은 요소가 활성화된다"는 대조가 깨져 잡힌다.
import { readFileSync } from "node:fs";
import vm from "node:vm";

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

// 설정 영역 컨트롤은 getElementById와 영역 querySelectorAll이 같은
// 객체를 돌려줘야 "같은 요소" 대조가 성립한다.
const areaControls = [];
for (const t of [...areaInputs, ...areaButtons]) {
  const e = el(t.id);
  e.className = t.cls;
  if (t.dataFor) e.setAttribute("data-for", t.dataFor);
  if (t.dataDir) e.setAttribute("data-dir", t.dataDir);
  areaControls.push(e);
}
const area = el("settingsArea");
area.querySelectorAll = () => areaControls.slice();
el("numOverlay").classList.add("hidden");
el("confirmOverlay").classList.add("hidden");

const byIdFromParse = (list) => list.filter((t) => t.id).map((t) => el(t.id));
const documentStub = {
  getElementById: (id) => el(id),
  querySelectorAll: (sel) => {
    if (sel === "input.slider") return byIdFromParse(sliders);
    if (sel === ".step-btn") return byIdFromParse(stepBtns);
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
    parseTradeSettings: (o) => o || {},
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
const slider = $("ts_bandLo");
check(!!slider, "lock: 슬라이더 요소가 존재한다");

// 1) 초기 상태는 잠금 — 요소는 그대로 있고 disabled다.
check(sandbox.settingsLocked === true, "lock: 초기 상태가 잠금이다");
check(slider.disabled === true, "lock: 잠금 상태에서 슬라이더가 disabled다");
check($("tempNoEnabled").disabled === true, "lock: 잠금 상태에서 기온 스위치가 disabled다");
check($("btnSettingsEdit").disabled === false, "lock: 편집 아이콘은 잠금 상태에서도 눌린다");

// 2) 편집 진입 — 같은 요소가 활성화되고, 변경이 없으면 저장 버튼은
//    그대로 비활성이다 (전부 켜는 구현을 막는다).
$("btnSettingsEdit").click();
check(sandbox.settingsLocked === false, "lock: 편집 아이콘으로 해제된다");
check(slider.disabled === false, "lock: 해제 시 같은 슬라이더 요소가 활성화된다");
check($("btnBandSave").disabled === true, "lock: 해제 직후 변경 없으면 저장 버튼은 비활성이다");

// 3) 값을 바꾸면 저장 버튼이 켜지고, 저장하면 다시 잠긴다.
sandbox.renderTradeSettings();
for (const g of Object.values(sandbox.TRADE_GROUPS)) {
  for (const f of g.fields) $(f.id).value = String(f.def);
}
slider.value = "0.65";
sandbox.updateSettingsUI();
check($("btnBandSave").disabled === false, "lock: 변경 후 저장 버튼이 활성화된다");
$("btnBandSave").click();
check(sandbox.settingsLocked === true, "lock: 저장하면 다시 잠긴다");
check(slider.disabled === true, "lock: 저장 후 슬라이더가 다시 disabled다");
check(JSON.parse(lsMap.get("bm_settings")).bandLo === 0.65, "lock: 저장값이 실제로 기록됐다");

// 4) 되돌리기(저장본으로)도 확인 팝업을 거친 뒤 다시 잠긴다.
$("btnSettingsEdit").click();
check(slider.disabled === false, "lock: 재해제 시 슬라이더가 활성화된다");
$("ts_bandHi").value = "0.85";
sandbox.updateSettingsUI();
$("btnBandSaved").click();
check(!$("confirmOverlay").classList.contains("hidden"), "lock: 되돌리기가 확인 팝업을 연다");
$("btnConfirmOk").click();
check(sandbox.settingsLocked === true, "lock: 되돌리기 후 다시 잠긴다");
check(Number($("ts_bandHi").value) === 0.9, "lock: 되돌리기로 폼이 저장본으로 돌아갔다");

// 5) 잠금 상태에서는 값 표시를 눌러도 숫자 입력 시트가 열리지 않는다.
sandbox.openNumSheet({ inputId: "ts_volumeMin", dispId: null, label: "최소 거래량" });
check($("numOverlay").classList.contains("hidden"), "lock: 잠금 상태에서 숫자 입력 시트가 열리지 않는다");
$("btnSettingsEdit").click();
sandbox.openNumSheet({ inputId: "ts_volumeMin", dispId: null, label: "최소 거래량" });
check(!$("numOverlay").classList.contains("hidden"), "lock: 해제 상태에서는 숫자 입력 시트가 열린다");
sandbox.closeNumSheet();
$("btnSettingsEdit").click();
check(sandbox.settingsLocked === true, "lock: 편집 아이콘 재클릭으로 다시 잠긴다");

console.log(`settings-lock tests: ${passed} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
