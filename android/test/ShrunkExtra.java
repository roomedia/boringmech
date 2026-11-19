import com.roomedia.boringmech.crypto.CallData;
import com.roomedia.boringmech.write.RedeemSubmitter;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 축소 스모크 보조 검증 — 골든 38건이 닿지 않는 쓰기 경로 직렬화를
 * 축소 jar로 실행한다 (run_shrunk_smoke.sh가 컴파일·실행).
 *
 * 대상: RedeemSubmitter.submitBody — 리딤 제출 본문 직렬화. 기대값은
 * native-golden4/golden4.json의 redeemBody 케이스와 같은 값이다
 * (한쪽이 바뀌면 양쪽을 함께 고쳐야 한다).
 */
public final class ShrunkExtra {
    public static void main(String[] args) {
        List<CallData> calls = new ArrayList<>();
        calls.add(new CallData(
                "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045", BigInteger.ZERO,
                new byte[]{0x12, 0x34, (byte) 0xab, (byte) 0xcd}));
        calls.add(new CallData(
                "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045", BigInteger.ZERO, new byte[0]));
        String sig = "0x" + "ab".repeat(65);
        String body = RedeemSubmitter.submitBody(
                "0x19E7E376E7C213B7E7e7e46cc70A5dD086DAff2A",
                "0x7a57144fAfde87F5cE4a880D570BAe58eeBC83f2",
                "7", sig, "0xcond123", "1760000600", calls);
        String expected = "{\"type\":\"WALLET\",\"from\":\"0x19E7E376E7C213B7E7e7e46cc70A5dD086DAff2A\","
                + "\"to\":\"0x00000000000Fb5C9ADea0298D729A0CB3823Cc07\",\"nonce\":\"7\","
                + "\"signature\":\"" + sig + "\","
                + "\"metadata\":\"Redeem positions for condition 0xcond123\","
                + "\"depositWalletParams\":{\"depositWallet\":\"0x7a57144fAfde87F5cE4a880D570BAe58eeBC83f2\","
                + "\"deadline\":\"1760000600\",\"calls\":["
                + "{\"target\":\"0x4D97DCd97eC945f40cF65F87097ACe5EA0476045\",\"value\":\"0\",\"data\":\"0x1234abcd\"},"
                + "{\"target\":\"0x4D97DCd97eC945f40cF65F87097ACe5EA0476045\",\"value\":\"0\",\"data\":\"0x\"}]}}";
        if (!expected.equals(body)) {
            System.out.println("shrunk extra FAILED:\n  expected: " + expected + "\n  actual:   " + body);
            System.exit(1);
        }
        System.out.println("shrunk extra: redeem submitBody OK");
    }
}
