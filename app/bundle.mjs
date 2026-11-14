// engine.js 번들 재생성 — 앱 버전을 package.json에서 읽어 esbuild define으로
// 번들에 상수로 박는다 (엔진의 APP_VERSION). node_modules가 있는 사본에서 실행:
//   npm install && npm run bundle
import { build } from "esbuild";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));
const orderBridgePath = fileURLToPath(new URL("./src/order-bridge.js", import.meta.url));

// 주문 전송 shim — SDK http-helpers의 post는 axios라서, CLOB /order 응답의
// ACAO(polymarket.com 고정)를 WebView가 막아 전부 Network Error가 된다.
// export 라인을 감싸 /order·/orders만 네이티브 브리지로 보내게 교체한다.
// SDK 버전이 바뀌어 export 라인이 달라지면 빌드를 실패시켜 조용한 미적용을 막는다.
const SDK_EXPORT_LINE =
  "export { DELETE, GET, POST, del, get, parseDropNotificationParams, post };";
const nativeOrderPost = {
  name: "native-order-post",
  setup(b) {
    b.onLoad(
      { filter: /clob-client-v2[/\\]dist[/\\]http-helpers[/\\]index\.js$/ },
      (args) => {
        const src = readFileSync(args.path, "utf8");
        if (!src.includes(SDK_EXPORT_LINE)) {
          throw new Error(
            "SDK http-helpers의 export 라인이 예상과 다릅니다 — 주문 shim을 적용할 수 없습니다",
          );
        }
        const patched = src.replace(
          SDK_EXPORT_LINE,
          `import { bridgePost, isOrderPostUrl } from ${JSON.stringify(orderBridgePath)};
const __origPost = post;
async function __nativeAwarePost(endpoint, options, retryOnError = false) {
  const bridge = globalThis.AndroidBridge;
  if (bridge && typeof bridge.postSync === "function" && isOrderPostUrl(endpoint)) {
    return bridgePost(endpoint, options, retryOnError, bridge);
  }
  return __origPost(endpoint, options, retryOnError);
}
export { DELETE, GET, POST, del, get, parseDropNotificationParams, __nativeAwarePost as post };`,
        );
        return { contents: patched, loader: "js" };
      },
    );
  },
};

await build({
  entryPoints: ["src/engine.js"],
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "es2020",
  minify: true,
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [nativeOrderPost],
  outfile: "engine.js",
});

// shim이 실제로 번들에 들어갔는지 확인 — 안 들어갔으면 주문이 다시 전건 죽는다
const bundled = readFileSync(new URL("./engine.js", import.meta.url), "utf8");
if (!bundled.includes("postSync")) {
  throw new Error("번들에 네이티브 주문 브리지 호출이 없습니다 — shim 미적용");
}
console.log(`bundled engine.js · APP_VERSION=${pkg.version} · 주문 shim 적용 확인`);
