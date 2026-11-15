package com.roomedia.boringmech.crypto;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * EIP-712 typed data 인코딩 — viem hashTypedData와 같은 결과를 낸다.
 *
 * 값 표현 규칙:
 *   uint/int  → BigInteger
 *   address   → "0x..." 문자열
 *   string    → String
 *   bytes     → byte[]
 *   bytes32   → byte[32]
 *   구조체    → Map&lt;String, Object&gt;
 *   배열 T[]  → List&lt;Object&gt;
 */
public final class Eip712 {
    private Eip712() {}

    public static final class Field {
        public final String name;
        public final String type;

        public Field(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    public static Map<String, List<Field>> types(Object... nameAndFields) {
        Map<String, List<Field>> out = new LinkedHashMap<>();
        for (int i = 0; i < nameAndFields.length; i += 2) {
            @SuppressWarnings("unchecked")
            List<Field> fields = (List<Field>) nameAndFields[i + 1];
            out.put((String) nameAndFields[i], fields);
        }
        return out;
    }

    public static List<Field> fields(Object... nameAndTypes) {
        List<Field> out = new ArrayList<>();
        for (int i = 0; i < nameAndTypes.length; i += 2) {
            out.add(new Field((String) nameAndTypes[i], (String) nameAndTypes[i + 1]));
        }
        return out;
    }

    public static Map<String, Object> value(Object... nameAndValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < nameAndValues.length; i += 2) {
            out.put((String) nameAndValues[i], nameAndValues[i + 1]);
        }
        return out;
    }

    /** primary 타입 선언 + 의존 구조체 선언(알파벳순)을 이어 붙인 타입 문자열. */
    public static String encodeType(String primaryType, Map<String, List<Field>> types) {
        Set<String> deps = new TreeSet<>();
        collectDeps(primaryType, types, deps);
        deps.remove(primaryType);
        StringBuilder sb = new StringBuilder();
        sb.append(typeDecl(primaryType, types));
        for (String dep : deps) {
            sb.append(typeDecl(dep, types));
        }
        return sb.toString();
    }

    private static void collectDeps(String type, Map<String, List<Field>> types, Set<String> into) {
        if (!types.containsKey(type) || into.contains(type)) {
            return;
        }
        into.add(type);
        for (Field f : types.get(type)) {
            String base = baseType(f.type);
            if (types.containsKey(base)) {
                collectDeps(base, types, into);
            }
        }
    }

    private static String baseType(String type) {
        String t = type;
        while (t.endsWith("]")) {
            int idx = t.lastIndexOf('[');
            if (idx < 0) {
                break;
            }
            t = t.substring(0, idx);
        }
        return t;
    }

    private static String typeDecl(String type, Map<String, List<Field>> types) {
        StringBuilder sb = new StringBuilder(type).append('(');
        List<Field> fields = types.get(type);
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(fields.get(i).type).append(' ').append(fields.get(i).name);
        }
        return sb.append(')').toString();
    }

    public static byte[] typeHash(String typeName, Map<String, List<Field>> types) {
        return Keccak.hash256(encodeType(typeName, types).getBytes(StandardCharsets.UTF_8));
    }

    /** 필드 1개의 EIP-712 인코딩 (항상 32바이트). */
    @SuppressWarnings("unchecked")
    public static byte[] encodeValue(String type, Object value, Map<String, List<Field>> types) {
        if (type.endsWith("]")) {
            String elemType = type.substring(0, type.lastIndexOf('['));
            List<Object> list = (List<Object>) value;
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            for (Object elem : list) {
                byte[] enc = encodeValue(elemType, elem, types);
                bos.write(enc, 0, enc.length);
            }
            return Keccak.hash256(bos.toByteArray());
        }
        if (types.containsKey(type)) {
            return hashStruct(type, types, (Map<String, Object>) value);
        }
        if (type.equals("string")) {
            return Keccak.hash256(((String) value).getBytes(StandardCharsets.UTF_8));
        }
        if (type.equals("bytes")) {
            return Keccak.hash256((byte[]) value);
        }
        if (type.startsWith("bytes")) {
            byte[] b = (byte[]) value;
            byte[] out = new byte[32];
            System.arraycopy(b, 0, out, 0, b.length);
            return out;
        }
        if (type.equals("address")) {
            return Abi.wordAddress((String) value);
        }
        if (type.equals("bool")) {
            return Abi.word(Boolean.TRUE.equals(value) ? BigInteger.ONE : BigInteger.ZERO);
        }
        if (type.startsWith("uint") || type.startsWith("int")) {
            return Abi.word((BigInteger) value);
        }
        throw new IllegalArgumentException("unsupported EIP-712 type: " + type);
    }

    public static byte[] hashStruct(String typeName, Map<String, List<Field>> types, Map<String, Object> value) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] th = typeHash(typeName, types);
        bos.write(th, 0, th.length);
        for (Field f : types.get(typeName)) {
            byte[] enc = encodeValue(f.type, value.get(f.name), types);
            bos.write(enc, 0, enc.length);
        }
        return Keccak.hash256(bos.toByteArray());
    }

    /**
     * 도메인 separator. 도메인 타입은 들어 있는 키만으로 조립한다
     * (name, version, chainId, verifyingContract, salt 순 — viem과 동일).
     */
    public static byte[] hashDomain(Map<String, Object> domain) {
        List<Field> fields = new ArrayList<>();
        if (domain.containsKey("name")) {
            fields.add(new Field("name", "string"));
        }
        if (domain.containsKey("version")) {
            fields.add(new Field("version", "string"));
        }
        if (domain.containsKey("chainId")) {
            fields.add(new Field("chainId", "uint256"));
        }
        if (domain.containsKey("verifyingContract")) {
            fields.add(new Field("verifyingContract", "address"));
        }
        if (domain.containsKey("salt")) {
            fields.add(new Field("salt", "bytes32"));
        }
        Map<String, List<Field>> types = new LinkedHashMap<>();
        types.put("EIP712Domain", fields);
        return hashStruct("EIP712Domain", types, domain);
    }

    /** 최종 서명 다이제스트: keccak256(0x1901 || domainSeparator || hashStruct(message)). */
    public static byte[] digest(Map<String, Object> domain, String primaryType,
                                  Map<String, List<Field>> types, Map<String, Object> message) {
        byte[] data = Abi.concat(
                new byte[]{0x19, 0x01},
                hashDomain(domain),
                hashStruct(primaryType, types, message));
        return Keccak.hash256(data);
    }

    /** typed data 전체를 서명해 65바이트 서명을 돌려준다. */
    public static byte[] sign(BigInteger privateKey, Map<String, Object> domain, String primaryType,
                              Map<String, List<Field>> types, Map<String, Object> message) {
        return Secp256k1.signHash(privateKey, digest(domain, primaryType, types, message));
    }
}
