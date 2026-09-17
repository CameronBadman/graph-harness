import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from report import summarize


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


if __name__ == "__main__":
    unittest.main()
