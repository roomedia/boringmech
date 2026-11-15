package com.roomedia.boringmech.engine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * org.json 트리 → Js·PositionBook이 쓰는 JSON 모델(Map/List/Double) 변환.
 * 숫자 규약(Double 통일)과 null 규약(JSONObject.NULL → Java null)을
 * Js 클래스의 모델 규약에 맞춘다.
 */
public final class JsonModel {
    private JsonModel() {}

    public static Object fromJson(Object v) {
        if (v == null || v == JSONObject.NULL) {
            return null;
        }
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            Map<String, Object> m = new LinkedHashMap<>();
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String k = it.next();
                m.put(k, fromJson(o.opt(k)));
            }
            return m;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            List<Object> l = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                l.add(fromJson(a.opt(i)));
            }
            return l;
        }
        if (v instanceof Number) {
            return ((Number) v).doubleValue();
        }
        return v; // String, Boolean은 그대로
    }
}
