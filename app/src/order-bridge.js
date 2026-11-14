// 주문 POST 전송 교체 — CLOB /order·/orders의 실제 응답은
// Access-Control-Allow-Origin이 https://polymarket.com 고정이라
// WebView(오리진 null)가 응답 읽기를 차단하고 axios가 전부
// "Network Error"로 죽는다. 서명·헤더·본문은 SDK가 만든 것을 그대로
// 네이티브 브리지(HttpURLConnection)로 보내 CORS 자체를 우회한다.
// 반환 모양은 SDK http-helpers의 post/errorHandling과 동일하게 맞춘다.

export function isOrderPostUrl(endpoint) {
  if (typeof endpoint !== "string") return false;
  let path = endpoint;
  try {
    path = new URL(endpoint).pathname;
  } catch {
    path = endpoint.split("?")[0];
  }
  return path === "/order" || path === "/orders";
}

const sleep = (ms) => new Promise((res) => setTimeout(res, ms));

// 네이티브 결과 {status, body, error}를 SDK post의 반환 모양으로 변환한다.
// status 0 = 네트워크 단계 실패 (axios의 무응답 케이스 {error: message}와 동일).
function shapeNativeResult({ status, body, error }) {
  if (!status) return { error: error || "Network Error" };
  let parsed = null;
  let isObject = false;
  if (body) {
    try {
      parsed = JSON.parse(body);
      isObject = parsed !== null && typeof parsed === "object";
    } catch {
      // JSON이 아닌 본문은 문자열 그대로 취급 (axios .data와 동일)
    }
  }
  if (status >= 200 && status < 300) {
    return isObject ? parsed : body || "";
  }
  // 비2xx — SDK errorHandling 흉내
  if (body) {
    if (!isObject) return { error: body, status };
    if (!Object.prototype.hasOwnProperty.call(parsed, "error")) {
      return { error: parsed, status };
    }
    return { ...parsed, status };
  }
  return { error: `Request failed with status code ${status}` };
}

async function nativePostOnce(bridge, endpoint, options) {
  const headers = (options && options.headers) || {};
  const data = options ? options.data : undefined;
  const bodyText =
    typeof data === "string" ? data : JSON.stringify(data ?? {});
  let raw;
  try {
    raw = bridge.postSync(endpoint, JSON.stringify(headers), bodyText);
  } catch (e) {
    return { status: 0, body: "", error: String((e && e.message) || e) };
  }
  try {
    const r = JSON.parse(raw);
    return {
      status: Number(r.status) || 0,
      body: typeof r.body === "string" ? r.body : "",
      error: r.error ? String(r.error) : null,
    };
  } catch {
    return { status: 0, body: "", error: "bridge 응답을 해석하지 못했습니다" };
  }
}

// SDK post와 같은 시그니처. retryOnError면 무응답·5xx에 한해 1회 재시도한다
// (원본 isTransientAxiosError의 범위와 동일).
export async function bridgePost(endpoint, options, retryOnError, bridge) {
  let r = await nativePostOnce(bridge, endpoint, options);
  if (retryOnError && (r.status === 0 || (r.status >= 500 && r.status < 600))) {
    await sleep(30);
    r = await nativePostOnce(bridge, endpoint, options);
  }
  return shapeNativeResult(r);
}
