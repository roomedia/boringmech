// engine.js 번들 재생성 — 앱 버전을 package.json에서 읽어 esbuild define으로
// 번들에 상수로 박는다 (엔진의 APP_VERSION). node_modules가 있는 사본에서 실행:
//   npm install && npm run bundle
import { build } from "esbuild";
import { readFileSync } from "node:fs";

const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));

await build({
  entryPoints: ["src/engine.js"],
  bundle: true,
  platform: "browser",
  format: "iife",
  target: "es2020",
  minify: true,
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  outfile: "engine.js",
});
console.log(`bundled engine.js · APP_VERSION=${pkg.version}`);
