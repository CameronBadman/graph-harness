import sys
from pathlib import Path
import unittest
import json
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import collect, summarize
from run import digest


def row(arm, input_tokens, output_tokens, correct=True, valid=True):
    return {
        "id": arm, "arm": arm, "task": "task", "repetition": 1,
        "valid_measurement": valid, "correctness": {"passed": correct},
        "usage": {"input_tokens": input_tokens, "cached_input_tokens": 0,
                  "uncached_input_tokens": input_tokens, "output_tokens": output_tokens,
                  "reasoning_output_tokens": 0, "total_tokens": input_tokens + output_tokens},
        "diagnostics": {"tool_calls": {}, "retrieval_used": False},
    }


class ReportTests(unittest.TestCase):
    def test_lower_tokens_with_wrong_answer_are_not_correct_pair(self):
        result = summarize([row("native", 100, 20), row("lean", 50, 10, correct=False)])
        self.assertEqual(result["arms"]["lean"]["correct"], 0)
        pair = next(pair for pair in result["pairs"] if pair["baseline"] == "native")
        self.assertFalse(pair["both_correct"])
        self.assertEqual(pair["percent_change"]["input_tokens"], -50)

    def test_invalid_measurements_remain_attempts_but_not_savings(self):
        result = summarize([row("native", 100, 20), row("lean", 10, 2, valid=False)])
        self.assertEqual(result["arms"]["lean"]["attempts"], 1)
        self.assertEqual(result["arms"]["lean"]["valid"], 0)
        self.assertEqual(result["pairs"], [])

    def test_totals_do_not_sum_reasoning_twice(self):
        record = row("native", 100, 20)
        record["usage"]["reasoning_output_tokens"] = 15
        result = summarize([record])
        self.assertEqual(result["arms"]["native"]["totals"]["total_tokens"], 120)

    def test_missing_raw_evidence_cannot_be_authorized_by_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            frozen = {"schedule": [{"id": "one", "task": "task", "arm": "native", "repetition": 1}],
                      "tasks": {"task": {"prompt": "task", "manifest_sha256": "fixture"}}}
            (base / "frozen.json").write_text(json.dumps(frozen))
            record = {**row("native", 100, 20), "id": "one", "raw_events_sha256": "missing-evidence",
                      "frozen_sha256": digest((base / "frozen.json").read_bytes()),
                      "prompt_sha256": digest(b"task"), "fixture_sha256": "fixture",
                      "workspace_before_sha256": "fixture", "instrumentation_valid": True}
            (base / "results.json").write_text(json.dumps([record]))
            (base / "access-review.json").write_text(json.dumps({"one": {
                "passed": True, "raw_events_sha256": "missing-evidence", "auditor": "test reviewer",
                "method": "unit fixture", "violations": []}}))
            with self.assertRaisesRegex(ValueError, "raw evidence missing"):
                collect(base)


if __name__ == "__main__":
    unittest.main()
