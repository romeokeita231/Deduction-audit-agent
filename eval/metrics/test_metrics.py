import json
import tempfile
import unittest
from pathlib import Path

from .metrics import (MetricsError, actual_state, compute, expected_state, load_rows,
                      metric1, metric2, metric3, policy_sha256, skeleton_passes)

CONTRACT = {
    "summaryWords": {"min": 120, "max": 250},
    "paragraphs": {"min": 3, "max": 5},
    "tokenizer": "whitespace",
    "amount": "480.00",
}

NARRATIVE = (
    "This letter concerns the shortage deduction of 480.00 USD raised under "
    "EDI812:11111111-1111-1111-1111-111111111111. We reviewed the shipping record and the delivery "
    "record, and we conclude that the deduction is not supported by the documents the retailer "
    "attached.\n\n"
    "The bill of lading BOL:SHP-2 shows that the units left our warehouse in good condition and were "
    "handed to the carrier. The proof of delivery POD:SHP-2 confirms that fewer units were accepted "
    "at the destination. The gap therefore opened while the goods were in transit, after they had "
    "left our control.\n\n"
    "Because the shortage did not occur on our side, we ask you to reverse the deduction in full and "
    "to issue a credit memo for the same amount to our account.\n\n"
    "Please confirm the reversal in writing. We remain available to provide any further documentation "
    "you may need, and we look forward to closing this case promptly."
)

CLAIM = {
    "deductionId": "11111111-1111-1111-1111-111111111111",
    "attemptNo": 1,
    "category": "SHORTAGE",
    "amount": "480.00",
    "currency": "USD",
    "ask": "REVERSE_DEDUCTION",
    "references": ["EDI812:11111111-1111-1111-1111-111111111111"],
    "supportingEvidence": ["BOL:SHP-2", "POD:SHP-2"],
    "basis": [],
}


def row(case_id, expected, verdict, parked=None, expected_parked=None, claim=None,
        narrative=None, expected_support=None):
    return {
        "schemaVersion": 1,
        "caseId": case_id,
        "attemptNo": 1,
        "status": "AWAITING_ACTION",
        "parkedReason": parked,
        "verdict": verdict,
        "claim": claim,
        "narrative": narrative,
        "digest": None,
        "latency": 12,
        "toolCalls": [],
        "contract": CONTRACT,
        "oracleBranch": "branch",
        "expectedState": expected,
        "expectedParkedReason": expected_parked,
        "expectedSupportingEvidence": expected_support or [],
    }


def fixture():
    return [
        row("SHORTAGE-SUPPORTS_SUPPLIER-01", "DEDUCTION_STANDS", "DEDUCTION_STANDS",
            expected_support=["BOL:SHP-1", "POD:SHP-1"]),
        row("SHORTAGE-SUPPORTS_RETAILER-01", "DISPUTABLE", "DISPUTABLE", claim=CLAIM,
            narrative=NARRATIVE, expected_support=["BOL:SHP-2", "POD:SHP-2"]),
        row("PRICE-CONFLICT_UNARBITRATED-01", "INCONCLUSIVE", "INCONCLUSIVE",
            parked="POLICY_GAP", expected_parked="POLICY_GAP"),
        row("PROMOTION-EVIDENCE_MISSING-01", "INCONCLUSIVE", "DEDUCTION_STANDS",
            expected_parked="EVIDENCE_GAP"),
    ]


class Metric1Test(unittest.TestCase):

    def test_reports_k_over_n_without_averaging_states(self):
        result = metric1(fixture())
        self.assertEqual({"k": 3, "n": 4}, result["microExactMatch"])
        self.assertEqual({"k": 1, "n": 1}, result["stateRecall"]["DEDUCTION_STANDS"])
        self.assertEqual({"k": 1, "n": 1}, result["stateRecall"]["DISPUTABLE"])
        self.assertEqual({"k": 1, "n": 1}, result["stateRecall"]["POLICY_GAP"])
        self.assertEqual({"k": 0, "n": 1}, result["stateRecall"]["EVIDENCE_GAP"])
        self.assertEqual({"k": 2, "n": 2}, result["categoryExactMatch"]["SHORTAGE"])
        self.assertEqual({"k": 1, "n": 1}, result["categoryExactMatch"]["PRICE_VARIANCE"])
        self.assertEqual({"k": 0, "n": 1}, result["categoryExactMatch"]["PROMOTION"])

    def test_confusion_appendix_covers_every_cell_and_state(self):
        confusion = metric1(fixture())["confusion"]
        self.assertEqual(16, len(confusion))
        perfect = [entry for entry in confusion
                   if entry["category"] == "SHORTAGE" and entry["situation"] == "SUPPORTS_RETAILER"]
        self.assertEqual({"category": "SHORTAGE", "situation": "SUPPORTS_RETAILER",
                          "state": "DISPUTABLE", "n": 1},
                         [entry for entry in perfect if entry["state"] == "DISPUTABLE"][0])

    def test_empty_state_bucket_fails(self):
        rows = [entry for entry in fixture() if expected_state(entry) != "EVIDENCE_GAP"]
        with self.assertRaises(MetricsError):
            metric1(rows)

    def test_inconclusive_maps_to_parked_reason(self):
        sample = row("PRICE-FOO-01", "INCONCLUSIVE", "INCONCLUSIVE", parked="POLICY_GAP",
                     expected_parked="POLICY_GAP")
        self.assertEqual("POLICY_GAP", expected_state(sample))
        self.assertEqual("POLICY_GAP", actual_state(sample))


class Metric2Test(unittest.TestCase):

    def test_exclusions_add_up_to_the_corpus_size(self):
        result = metric2(fixture())
        self.assertEqual({"oracleDisputable": 1}, result["denominator"])
        self.assertEqual({"noClaim": 1, "wrongField": 1, "unarbitrated": 1}, result["exclusions"])
        total = result["denominator"]["oracleDisputable"] + sum(result["exclusions"].values())
        self.assertEqual(4, total)
        self.assertEqual({"k": 2, "n": 2}, result["recall"])
        self.assertEqual({"k": 2, "n": 2}, result["precision"])

    def test_empty_references_report_zero_recall_and_undefined_precision(self):
        empty_claim = dict(CLAIM, supportingEvidence=[])
        rows = fixture()
        rows[1] = dict(rows[1], claim=empty_claim)
        result = metric2(rows)
        self.assertEqual({"k": 0, "n": 2}, result["recall"])
        self.assertEqual("undefined", result["precision"]["value"])
        self.assertEqual(1, result["zeroReferenceCases"])

    def test_disputable_without_claim_is_counted_but_not_scored(self):
        rows = fixture()
        rows[1] = dict(rows[1], claim=None, narrative=None)
        result = metric2(rows)
        self.assertEqual(0, result["claimed"])
        self.assertEqual(0, result["precision"]["n"])
        self.assertEqual("undefined", result["precision"]["value"])


class Metric3Test(unittest.TestCase):

    def test_structural_pass_rate_and_production_rate_stay_separate(self):
        rows = fixture()
        rows.append(row("SHORTAGE-CONFLICT_ARBITRATED-01", "DISPUTABLE", "DISPUTABLE", claim=CLAIM,
                        narrative=None, expected_support=["BOL:SHP-2"]))
        result = metric3(rows)
        self.assertEqual({"k": 1, "n": 1}, result["structuralPass"])
        self.assertEqual({"k": 1, "n": 2}, result["productionRate"])

    def test_skeleton_uses_character_offsets(self):
        self.assertTrue(skeleton_passes(fixture()[1]))
        broken = dict(fixture()[1])
        broken["narrative"] = NARRATIVE.replace("480.00 USD raised", "raised")
        self.assertFalse(skeleton_passes(broken))

    def test_skeleton_rejects_reversed_order(self):
        broken = dict(fixture()[1])
        broken["narrative"] = NARRATIVE.replace(
            "This letter concerns the shortage deduction of 480.00 USD",
            "BOL:SHP-2 came before the shortage deduction of 480.00 USD")
        self.assertFalse(skeleton_passes(broken))


class ComputeAndLoadTest(unittest.TestCase):

    def test_policy_hash_matches_the_value_java_wrote_into_the_baseline(self):
        root = Path(__file__).resolve().parents[2]
        baseline = json.loads((root / "src/test/resources/eval/baseline/metrics.json")
                              .read_text(encoding="utf-8"))
        self.assertEqual(baseline["policy"]["sha256"],
                         policy_sha256(root / "src/main/resources/policy/audit-policy.md"))

    def test_compute_carries_provenance_and_schema_version(self):
        report = compute(fixture(), "c", "p", "sha")
        self.assertEqual(1, report["schemaVersion"])
        self.assertEqual("c", report["corpus"]["sha256"])
        self.assertEqual(4, report["corpus"]["cases"])
        self.assertEqual("p", report["policy"]["sha256"])
        self.assertEqual("sha", report["commit"])

    def test_load_rows_rejects_mixed_versions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.ndjson"
            first = dict(fixture()[0])
            second = dict(fixture()[1], schemaVersion=2)
            path.write_text(json.dumps(first) + "\n" + json.dumps(second) + "\n", encoding="utf-8")
            with self.assertRaises(MetricsError):
                load_rows(path)

    def test_load_rows_accepts_ndjson(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "results.ndjson"
            path.write_text("\n".join(json.dumps(entry) for entry in fixture()) + "\n",
                            encoding="utf-8")
            rows = load_rows(path)
            self.assertEqual(4, len(rows))


if __name__ == "__main__":
    unittest.main()
