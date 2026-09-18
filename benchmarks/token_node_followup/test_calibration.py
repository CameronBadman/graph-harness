from pathlib import Path
import tempfile
import unittest

from benchmarks.token_node_followup import run_trials as trials


class CalibrationTests(unittest.TestCase):
    def check_body(self, body):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'Gauge.java').write_text('public class Gauge { public int reading() {' + body + '} }\n')
            return trials.calibration_behavior(root)

    def test_equivalent_nested_block_and_expression_pass(self):
        self.assertTrue(self.check_body('{ return 38; }'))
        self.assertTrue(self.check_body('return 19 * 2;'))

    def test_wrong_behavior_invalid_source_and_early_exit_fail(self):
        for body in ('return 37;', 'return (;', 'System.exit(0); return 38;'):
            with self.subTest(body=body):
                self.assertFalse(self.check_body(body))

    def test_scope_escape_and_extra_source_fail(self):
        self.assertFalse(self.check_body('return 38;} int injected() { return 1;'))
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'Gauge.java').write_text('public class Gauge { public int reading() {return 38;} }\n')
            (root / 'extra.txt').write_text('unexpected')
            self.assertFalse(trials.calibration_behavior(root))

    def test_observed_failed_calibration_is_a_valid_repair(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / 'Gauge.java').write_text('public class Gauge { public int reading() {\n    { return 38; }\n} }\n')
            diagnostic = {'tool_calls': {'mcp:build_context_bundle': 1}, 'node_write_commits': 1}
            self.assertEqual(trials.calibration_result(root, '{"reading":38}', diagnostic, 'node'), {'passed': True})
