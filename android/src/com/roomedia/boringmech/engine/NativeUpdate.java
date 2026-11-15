package com.roomedia.boringmech.engine;

import com.roomedia.boringmech.write.ModelJson;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub 릴리스 업데이트 확인 — engine.js parseVersion/compareVersions/parseLatestRelease 이식.
 * 사이클 오케스트레이션(NativeCycle)이 30사이클마다 최신 릴리스를 조회해 이 판정을 쓴다.
 */
public final class NativeUpdate {
    private NativeUpdate() {}

    private static final Pattern VERSION_RE = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)$");
    private static final Pattern ASSET_RE = Pattern.compile("^boringmech-v.+\\.apk$");

    /** "v0.3.28" → [0,3,28]. 형식이 아니면 null (JS parseVersion의 null과 같다). */
    public static int[] parseVersion(String v) {
        if (v == null) {
            return null;
        }
        Matcher m = VERSION_RE.matcher(v.trim());
        if (!m.matches()) {
            return null;
        }
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
    }

    /** a가 b보다 크면 1, 같으면 0, 작으면 -1. 어느 한쪽이라도 파싱 실패면 null. */
    public static Integer compareVersions(String a, String b) {
        int[] pa = parseVersion(a);
        int[] pb = parseVersion(b);
        if (pa == null || pb == null) {
            return null;
        }
        for (int i = 0; i < 3; i++) {
            if (pa[i] != pb[i]) {
                return pa[i] > pb[i] ? 1 : -1;
            }
        }
        return 0;
    }

    public static final class Release {
        public final String tag;
        public final String assetName;
        public final String url;
        public final String digest;

        public Release(String tag, String assetName, String url, String digest) {
            this.tag = tag;
            this.assetName = assetName;
            this.url = url;
            this.digest = digest;
        }

        /** bm_update에 저장하는 JSON (JS rel 객체와 같은 모양). */
        public String toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tag", tag);
            m.put("assetName", assetName);
            m.put("url", url);
            m.put("digest", digest);
            return ModelJson.stringify(m);
        }
    }

    /**
     * GitHub /releases/latest 응답 모델 → Release. 태그 파싱 실패·APK 자산 없음·
     * 다운로드 URL 없음이면 null (JS parseLatestRelease와 같다).
     */
    public static Release parseLatestRelease(Object json) {
        if (!(json instanceof Map)) {
            return null;
        }
        Object tagRaw = Js.field(json, "tag_name");
        String tag = tagRaw instanceof String ? (String) tagRaw : null;
        if (parseVersion(tag) == null) {
            return null;
        }
        Object assets = Js.field(json, "assets");
        if (!(assets instanceof java.util.List)) {
            return null;
        }
        for (Object a : (java.util.List<?>) assets) {
            if (!(a instanceof Map)) {
                continue;
            }
            Object nameRaw = Js.field(a, "name");
            if (!(nameRaw instanceof String) || !ASSET_RE.matcher((String) nameRaw).matches()) {
                continue;
            }
            Object urlRaw = Js.field(a, "browser_download_url");
            if (!Js.truthy(urlRaw)) {
                continue;
            }
            Object digestRaw = Js.field(a, "digest");
            return new Release(tag, (String) nameRaw, Js.str(urlRaw),
                    digestRaw instanceof String ? (String) digestRaw : "");
        }
        return null;
    }
}
