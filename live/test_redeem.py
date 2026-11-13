#!/usr/bin/env python3
"""live_executor redeem 단위 테스트 — 전송 계층을 mock으로 대체한다.

네트워크·서명·실제 제출은 일절 없다. 검증 대상:
- build_redeem_call의 calldata가 통합 SDK 빌더와 바이트 단위로 같은지
- plan_redeems의 condition 그룹화·승/패 분류·worthless 분류
- execute_redeem_plan의 상태 전이·잔고 델타 검증·건별 실패 스킵(래치 없음)

실행: ~/workspace/.venv/bin/python -m unittest discover -s live -p 'test_*.py'
또는 이 파일을 직접 실행한다.
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import live_executor as ex  # noqa: E402

CID_A = "0x" + "aa" * 32
CID_B = "0x" + "bb" * 32
CID_C = "0x" + "cc" * 32
COLLATERAL = "0xC011a7E12a19f7B1f670d46F03B03f3342E82DFB"  # pUSD


def pending(cid, slug, status, value):
    return {"condition_id": cid, "slug": slug,
            "outcome": "Yes" if status == "REDEEMABLE" else "No",
            "status": status, "current_value": value}


class FakeTransport:
    """제출 성공/실패를 흉내내는 mock. prepare()도 성공으로 둔다."""

    name = "fake"

    def __init__(self, fail_cids=()):
        self.calls = []
        self.fail_cids = set(fail_cids)

    def prepare(self):
        pass

    def submit(self, condition_id):
        self.calls.append(condition_id)
        if condition_id in self.fail_cids:
            raise RuntimeError("relayer 500 (mock)")
        return {"tx_hash": "0xtx_" + condition_id[2:6],
                "transaction_id": "tid-1"}


class CashSeq:
    """잔고 조회 시퀀스 mock. Exception 인스턴스를 넣으면 그 차례에 던진다."""

    def __init__(self, values):
        self.values = list(values)

    def __call__(self):
        v = self.values.pop(0)
        if isinstance(v, Exception):
            raise v
        return v


class BuildCallTest(unittest.TestCase):
    def test_matches_sdk_builder(self):
        from polymarket.calls import ctf_redeem_positions_call

        sdk = ctf_redeem_positions_call(
            ctf=ex.CTF_ADDRESS, collateral=COLLATERAL, condition_id=CID_A)
        mine = ex.build_redeem_call(CID_A, COLLATERAL)
        self.assertEqual(mine["data"], str(sdk.data))
        self.assertEqual(mine["to"], str(sdk.to))
        self.assertEqual(mine["index_sets"], [1, 2])
        self.assertEqual(mine["parent_collection_id"], ex.ZERO_BYTES32)

    def test_bad_condition_id(self):
        with self.assertRaises(ValueError):
            ex.build_redeem_call("0x1234", COLLATERAL)
        with self.assertRaises(ValueError):
            ex.build_redeem_call(None, COLLATERAL)


class PlanTest(unittest.TestCase):
    def test_grouping_and_worthless(self):
        state = {"redeemable_pending": [
            pending(CID_A, "a-win", "REDEEMABLE", 5.25),
            pending(CID_A, "a-lose", "REDEEMABLE_LOST", 0.0),
            pending(CID_B, "b-lose", "REDEEMABLE_LOST", 0.0),
            pending(CID_C, "c-win", "REDEEMABLE", 3.0),
            {"condition_id": "bad", "slug": "broken",
             "status": "REDEEMABLE", "current_value": 1.0},
        ]}
        plan = ex.plan_redeems(state)
        self.assertEqual([t["condition_id"] for t in plan["targets"]],
                         [CID_A, CID_C])
        self.assertEqual(plan["targets"][0]["expected_usd"], 5.25)
        self.assertEqual(len(plan["targets"][0]["wins"]), 1)
        self.assertEqual(len(plan["targets"][0]["losses"]), 1)
        self.assertEqual([e["slug"] for e in plan["worthless"]], ["b-lose"])
        self.assertEqual(len(plan["errors"]), 1)

    def test_zero_value_win_is_not_a_target(self):
        state = {"redeemable_pending": [
            pending(CID_A, "a-zero", "REDEEMABLE", 0.0)]}
        plan = ex.plan_redeems(state)
        self.assertEqual(plan["targets"], [])
        self.assertEqual(len(plan["worthless"]), 1)


class ExecuteTest(unittest.TestCase):
    def base_state(self):
        return {"redeemable_pending": [
                    pending(CID_A, "a-win", "REDEEMABLE", 5.0),
                    pending(CID_B, "b-lose", "REDEEMABLE_LOST", 0.0)],
                "settled": [
                    {"condition_id": CID_A, "token_id": "t1", "won": True},
                    {"condition_id": CID_B, "token_id": "t2", "won": False}]}

    def test_success_flow_and_state_transition(self):
        state = self.base_state()
        plan = ex.plan_redeems(state)
        transport = FakeTransport()
        results = ex.execute_redeem_plan(state, plan, transport,
                                         CashSeq([100.0, 105.0]))
        # 패배만 있는 condition은 호출하지 않는다
        self.assertEqual(transport.calls, [CID_A])
        self.assertTrue(all(r["ok"] for r in results))
        self.assertEqual(state["redeemable_pending"], [])
        hist = state["redeem_history"]
        self.assertEqual(hist[0]["recovered_usd"], 5.0)
        self.assertEqual(hist[0]["expected_usd"], 5.0)
        self.assertEqual(hist[0]["verify_status"], "verified")
        self.assertEqual(hist[0]["tx_hash"], "0xtx_aaaa")
        self.assertTrue(state["settled"][0]["redeemed"])
        self.assertEqual(state["settled"][0]["redeem_tx"], "0xtx_aaaa")
        self.assertEqual(hist[1]["kind"], "worthless_close")
        self.assertEqual(hist[1]["recovered_usd"], 0.0)

    def test_failure_keeps_pending_and_no_latch(self):
        state = self.base_state()
        plan = ex.plan_redeems(state)
        transport = FakeTransport(fail_cids={CID_A})
        results = ex.execute_redeem_plan(state, plan, transport,
                                         CashSeq([100.0]))
        failed = [r for r in results if not r["ok"]]
        self.assertEqual(len(failed), 1)
        self.assertIn("relayer 500", failed[0]["error"])
        remaining = [e["condition_id"] for e in state["redeemable_pending"]]
        self.assertEqual(remaining, [CID_A])  # 실패 건은 pending 유지
        self.assertNotIn("blocked", state)    # 영구 래치를 만들지 않는다
        self.assertNotIn("block_reason", state)

    def test_submitted_unverified_closes_entry(self):
        # 제출 후 잔고 측정이 실패하면 중복 제출 방지를 위해 종결 처리한다
        state = self.base_state()
        plan = ex.plan_redeems(state)
        transport = FakeTransport()
        cash = CashSeq([100.0, RuntimeError("balance read failed")])
        results = ex.execute_redeem_plan(state, plan, transport, cash)
        first = [r for r in results if r["condition_id"] == CID_A][0]
        self.assertTrue(first["ok"])
        self.assertEqual(first["verify_status"], "submitted_unverified")
        self.assertIsNone(first["recovered_usd"])
        remaining = [e["condition_id"] for e in state["redeemable_pending"]]
        self.assertNotIn(CID_A, remaining)

    def test_balance_mismatch_still_recorded(self):
        state = self.base_state()
        plan = ex.plan_redeems(state)
        results = ex.execute_redeem_plan(state, plan, FakeTransport(),
                                         CashSeq([100.0, 101.0]))
        first = [r for r in results if r["condition_id"] == CID_A][0]
        self.assertEqual(first["verify_status"], "mismatch")
        self.assertEqual(first["recovered_usd"], 1.0)  # 실측 델타를 기록


class VerifyTest(unittest.TestCase):
    def test_tolerance(self):
        self.assertEqual(ex.verify_recovery(100.0, 105.03, 5.0)["status"],
                         "verified")
        self.assertEqual(ex.verify_recovery(100.0, 104.0, 5.0)["status"],
                         "mismatch")
        self.assertEqual(ex.verify_recovery(100.0, 100.0, 0.0)["delta_usd"],
                         0.0)


if __name__ == "__main__":
    unittest.main()
