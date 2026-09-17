import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run import ARMS, diagnostics, parse_usage, schedule


class AccountingTests(unittest.TestCase):
    def event(self):
        return {"type": "turn.completed", "usage": {
            "input_tokens": 1000, "cached_input_tokens": 700,
            "output_tokens": 200, "reasoning_output_tokens": 150,
            "cache_write_input_tokens": 0,
        }}

    def test_reasoning_and_cache_are_not_added_twice(self):
        result = parse_usage([self.event()])
        self.assertEqual(result["total_tokens"], 1200)
        self.assertEqual(result["uncached_input_tokens"], 300)
        self.assertEqual(result["output_tokens"], 200)

    def test_missing_duplicate_or_impossible_usage_fails(self):
        for events in ([], [self.event(), self.event()], [{"type": "turn.completed"}]):
            with self.assertRaises(ValueError):
                parse_usage(events)
        for key, value in (("cached_input_tokens", 1001), ("reasoning_output_tokens", 201),
                           ("output_tokens", -1), ("input_tokens", True)):
            event = self.event()
            event["usage"][key] = value
            with self.assertRaises(ValueError):
                parse_usage([event])

    def test_exact_registered_run_counts(self):
        runs = schedule()
        self.assertEqual(runs, schedule())
        self.assertEqual(len(runs), 24)
        self.assertEqual(len({run["id"] for run in runs}), 24)
        for arm in ARMS:
            self.assertEqual(sum(run["arm"] == arm for run in runs), 6)

    def test_repeated_tool_event_is_not_counted_twice(self):
        event = {"type": "item.completed", "item": {"id": "1", "type": "mcp_tool_call",
                 "tool": "get_source", "result": {"value": "public source"}}}
        result = diagnostics([event, event])
        self.assertEqual(result["tool_calls"], {"mcp:get_source": 1})
        self.assertTrue(result["retrieval_used"])


if __name__ == "__main__":
    unittest.main()
