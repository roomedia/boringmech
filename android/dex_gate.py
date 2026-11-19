#!/usr/bin/env python3
"""boringmech 출하 dex 게이트 — build.sh가 서명 직후에 실행한다.

소스·JVM 테스트는 R8 축소 전 바이트코드를 돌리므로, 축소가 만든 구조
결함(클래스 검증 실패)을 잡지 못한다 (v0.3.26 어노테이션 사고,
v0.3.33 서명 VerifyError). 이 게이트는 출하 APK의 classes.dex를
직접 파싱해 다음을 검사하고, 하나라도 실패하면 종료 코드 1로 빌드를
중단시킨다.

검사 항목:
1. instance-of / check-cast가 원시 타입을 참조하지 않는가 (전체 dex).
   — R8 enum unboxing이 enum에 대한 instanceof를 원시 타입 I에 대한
   instance-of로 바꿔 ART 검증기가 클래스 전체를 거부한 결함의 패턴.
2. dex 문자열 테이블에 JavascriptInterface 어노테이션이 있는가.
   — 없으면 브리지 메서드가 JS에 노출되지 않는다.
3. 쓰기 경로 앱 클래스가 dex에 전부 존재하는가.
4. 매핑 파일 기준으로 서명 경로 Bouncy Castle 클래스가 dex에 존재하는가.
   — CryptoServicePurpose가 없으면 enum이 unboxing된 것이다.
5. 수명주기 앱 클래스(외부 감시 리시버 등)가 dex에 전부 존재하는가.
   — 매니페스트가 가리키는 리시버가 축소로 사라지면 외부 감시가
   조용히 무력화된다 (v0.3.35에서 추가).

사용법: dex_gate.py <apk> <mapping.txt>
"""
import struct
import sys
import zipfile

PRIMITIVES = {"B", "C", "D", "F", "I", "J", "S", "V", "Z"}

# Dalvik opcode 길이 (16비트 코드 단위) — 표준 바이트코드 표 기준.
# unused 구간(0x3e-0x43, 0x73, 0x79, 0xe3-0xef, 0xf0-0xf9)은 표에
# 넣지 않는다: 출현하면 스캐너가 unknown-opcode로 게이트를 실패시킨다
# (길이를 지어내 정렬이 어긋난 채 통과시키는 것보다 정직한 실패가 낫다).
# 0x00은 페이로드 식별자(nop 포함)로 분기 처리한다.
OP_LEN = {0x00: 1}
def _fill(ops, n):
    for op in ops:
        OP_LEN[op] = n
_fill([0x01, 0x04, 0x07, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10, 0x11,
       0x12, 0x1d, 0x1e, 0x21, 0x27, 0x28], 1)                 # move·결과·const/4·모니터·throw·goto
_fill(range(0x7a, 0x90), 1)          # neg/not·형변환 (12x)
_fill(range(0xb0, 0xd0), 1)          # 이항 연산 /2addr (12x)
_fill([0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20,
       0x22, 0x23, 0x29], 2)
_fill(range(0x2d, 0x3e), 2)          # cmp·if (21t/22t)
_fill(range(0x44, 0x6e), 2)          # 배열·필드 접근 (22c/21c)
_fill(range(0x90, 0xb0), 2)          # 이항 연산 (23x)
_fill(range(0xd0, 0xe3), 2)          # 이항 리터럴 (22b/22s)
_fill([0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b,
       0x2c], 3)
_fill(range(0x6e, 0x73), 3)          # invoke (35c)
_fill(range(0x74, 0x79), 3)          # invoke/range (3rc) — 0x74~0x78 다섯 개
OP_LEN[0x18] = 5                      # const-wide (51l)
OP_LEN[0xfa] = 4                      # invoke-polymorphic (45cc)
OP_LEN[0xfb] = 4                      # invoke-polymorphic/range (4rc)
OP_LEN[0xfc] = 3                      # invoke-custom (35c)
OP_LEN[0xfd] = 3                      # invoke-custom/range (3rc)
OP_LEN[0xfe] = 2                      # const-method-handle (21c)
OP_LEN[0xff] = 2                      # const-method-type (21c)

# 쓰기 경로 앱 클래스 — 앱 패키지는 keep으로 이름이 보존되므로 기술자로 직접 본다.
WRITE_PATH_CLASSES = [
    "Lcom/roomedia/boringmech/crypto/OrderSigner;",
    "Lcom/roomedia/boringmech/crypto/Secp256k1;",
    "Lcom/roomedia/boringmech/crypto/Eip712;",
    "Lcom/roomedia/boringmech/crypto/ClobAuth;",
    "Lcom/roomedia/boringmech/write/ClobWriteClient;",
    "Lcom/roomedia/boringmech/write/StopLossSeller;",
    "Lcom/roomedia/boringmech/write/ManualSell;",
    "Lcom/roomedia/boringmech/write/RedeemSubmitter;",
    "Lcom/roomedia/boringmech/write/SubmitGuard;",
    "Lcom/roomedia/boringmech/engine/NativeCycle;",
]

# 수명주기 앱 클래스 — 매니페스트 컴포넌트와 외부 감시 체인. 앱 패키지는
# keep으로 이름이 보존되므로 기술자로 직접 본다.
LIFECYCLE_CLASSES = [
    "Lcom/roomedia/boringmech/EngineService;",
    "Lcom/roomedia/boringmech/MainActivity;",
    "Lcom/roomedia/boringmech/ExternalWatchdogReceiver;",
    "Lcom/roomedia/boringmech/ExternalWatchdogScheduler;",
    "Lcom/roomedia/boringmech/ExternalWatchdog;",
]

# 서명 경로 Bouncy Castle 클래스 — 난독화되므로 매핑으로 기술자를 얻는다.
SIGNING_BC_CLASSES = [
    "org.bouncycastle.crypto.constraints.DefaultServiceProperties",
    "org.bouncycastle.crypto.CryptoServicePurpose",
    "org.bouncycastle.crypto.digests.KeccakDigest",
    "org.bouncycastle.crypto.digests.SHA256Digest",
    "org.bouncycastle.crypto.signers.ECDSASigner",
]


def u32(d, o):
    return struct.unpack_from("<I", d, o)[0]


def u16(d, o):
    return struct.unpack_from("<H", d, o)[0]


def read_dex(apk_path):
    with zipfile.ZipFile(apk_path) as z:
        return z.read("classes.dex")


def parse_strings(d):
    n, off = u32(d, 0x38), u32(d, 0x3C)
    out = []
    for i in range(n):
        soff = u32(d, off + 4 * i)
        p = soff
        length = 0
        shift = 0
        while True:
            b = d[p]
            p += 1
            length |= (b & 0x7F) << shift
            shift += 7
            if not (b & 0x80):
                break
        out.append(d[p:p + length].decode("utf-8", "replace"))
    return out


def parse_types(d, strings):
    n, off = u32(d, 0x40), u32(d, 0x44)
    return [strings[u32(d, off + 4 * i)] for i in range(n)]


def uleb(d, off):
    r = 0
    s = 0
    p = off
    while True:
        b = d[p]
        p += 1
        r |= (b & 0x7F) << s
        s += 7
        if not (b & 0x80):
            break
    return r, p


def scan_malformed(d, types):
    """(원시 타입 참조 목록, instance-of/check-cast 총수)를 돌려준다.
    총수는 dexdump 같은 외부 도구와 교차 검증하는 용도다."""
    bad = []
    total = 0
    cd_n, cd_off = u32(d, 0x60), u32(d, 0x64)
    for ci in range(cd_n):
        base = cd_off + 32 * ci
        cidx = u32(d, base)
        cdata = u32(d, base + 24)
        if cdata == 0:
            continue
        p = cdata
        sf, p = uleb(d, p)
        inf, p = uleb(d, p)
        dm, p = uleb(d, p)
        vm, p = uleb(d, p)
        for _ in range(sf):
            _, p = uleb(d, p)
            _, p = uleb(d, p)
        for _ in range(inf):
            _, p = uleb(d, p)
            _, p = uleb(d, p)
        for group in (dm, vm):
            for _ in range(group):
                _, p = uleb(d, p)
                _, p = uleb(d, p)
                code, p = uleb(d, p)
                if code == 0:
                    continue
                insns_size = u32(d, code + 12)
                insns_off = code + 16
                i = 0
                while i < insns_size:
                    unit = u16(d, insns_off + 2 * i)
                    op = unit & 0xFF
                    if unit == 0x0100:  # packed-switch payload
                        size = u16(d, insns_off + 2 * (i + 1))
                        i += 4 + 2 * size
                        continue
                    if unit == 0x0200:  # sparse-switch payload
                        size = u16(d, insns_off + 2 * (i + 1))
                        i += 2 + 4 * size
                        continue
                    if unit == 0x0300:  # fill-array-data payload
                        width = u16(d, insns_off + 2 * (i + 1))
                        size = u32(d, insns_off + 2 * (i + 2))
                        i += 4 + (width * size + 1) // 2
                        continue
                    if op in (0x1F, 0x20):
                        total += 1
                        tidx = u16(d, insns_off + 2 * (i + 1))
                        if tidx < len(types) and types[tidx] in PRIMITIVES:
                            bad.append((types[cidx],
                                        "check-cast" if op == 0x1F else "instance-of",
                                        types[tidx]))
                    step = OP_LEN.get(op)
                    if step is None:
                        bad.append((types[cidx], "unknown-opcode", hex(op)))
                        break
                    i += step
    return bad, total


def class_descriptors(d):
    n, off = u32(d, 0x60), u32(d, 0x64)
    _, types = None, parse_types(d, parse_strings(d))
    return {types[u32(d, off + 32 * i)] for i in range(n)}


def load_mapping(path):
    mapping = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.startswith(" ") and " -> " in line and line.endswith(":"):
                original, obfuscated = line[:-1].split(" -> ")
                mapping[original.strip()] = obfuscated.strip()
    return mapping


def to_descriptor(name):
    return "L" + name.replace(".", "/") + ";"


def main():
    if len(sys.argv) != 3:
        print("usage: dex_gate.py <apk> <mapping.txt>", file=sys.stderr)
        return 2
    apk_path, mapping_path = sys.argv[1], sys.argv[2]
    failures = []

    d = read_dex(apk_path)
    strings = parse_strings(d)
    types = parse_types(d, strings)

    bad, total_casts = scan_malformed(d, types)
    if bad:
        for cls, kind, t in bad:
            failures.append(f"원시 타입 {kind}: {cls} -> {t}")
    else:
        print(f"gate OK: instance-of/check-cast 원시 타입 참조 0건 (총 {total_casts}건 검사)")

    if "Landroid/webkit/JavascriptInterface;" not in strings:
        failures.append("JavascriptInterface 어노테이션이 dex에 없음")
    else:
        print("gate OK: JavascriptInterface 존재")

    classes = class_descriptors(d)
    missing = [c for c in WRITE_PATH_CLASSES if c not in classes]
    if missing:
        failures.append("쓰기 경로 클래스 누락: " + ", ".join(missing))
    else:
        print("gate OK: 쓰기 경로 앱 클래스 전부 존재")

    missing_lc = [c for c in LIFECYCLE_CLASSES if c not in classes]
    if missing_lc:
        failures.append("수명주기 클래스 누락: " + ", ".join(missing_lc))
    else:
        print("gate OK: 수명주기 앱 클래스 전부 존재")

    mapping = load_mapping(mapping_path)
    missing_bc = []
    for original in SIGNING_BC_CLASSES:
        # keep으로 이름이 유지된 클래스는 매핑 파일에서 생략될 수 있다 —
        # 원본 기술자 그대로 존재해도 통과로 본다.
        if to_descriptor(original) in classes:
            continue
        obfuscated = mapping.get(original)
        if obfuscated is None or to_descriptor(obfuscated) not in classes:
            missing_bc.append(original)
    if missing_bc:
        failures.append("서명 경로 BC 클래스 누락(축소로 소실): " + ", ".join(missing_bc))
    else:
        print("gate OK: 서명 경로 BC 클래스 전부 존재")

    if failures:
        print("DEX GATE FAILED:", file=sys.stderr)
        for f in failures:
            print(" - " + f, file=sys.stderr)
        return 1
    print("DEX GATE PASSED")
    return 0


if __name__ == "__main__":
    sys.exit(main())
