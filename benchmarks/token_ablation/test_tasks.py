import json
from pathlib import Path
import subprocess
import tempfile
import unittest

from benchmarks.token_ablation.tasks import TASK_IDS, create_task, evaluate


class TaskTests(unittest.TestCase):
    def test_deterministic_complete_manifests(self):
        with tempfile.TemporaryDirectory() as directory:
            for task in TASK_IDS:
                root = Path(directory)
                a = create_task(task, root / (task + '-a'))
                b = create_task(task, root / (task + '-b'))
                self.assertEqual(a, b)
                self.assertIn('TASK.md', a['workspace_files'])
                with self.assertRaises(ValueError):
                    create_task(task, root / (task + '-a'))

    def test_navigation_oracles_and_malformed_answers(self):
        expected = {
            'java_chain': {'decision': 'ALLOW', 'sink': 'AuditLedger.appendApproved'},
            'polyglot_lookup': {'typescript_symbol': 'web/payments.ts:resolve',
                                'python_symbol': 'ops/payments.py:resolve'},
        }
        with tempfile.TemporaryDirectory() as directory:
            for task, answer in expected.items():
                root = Path(directory) / task
                create_task(task, root)
                self.assertTrue(evaluate(task, root, json.dumps(answer))['passed'])
                for bad in ('[]', '{}', 'null', 'not JSON', json.dumps(dict(answer, extra='x'))):
                    self.assertFalse(evaluate(task, root, bad)['passed'])
                (root / 'TASK.md').write_text('changed')
                self.assertFalse(evaluate(task, root, json.dumps(answer))['passed'])

    def test_java_chain_answer_matches_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'source'
            create_task('java_chain', root)
            (root / 'Check.java').write_text('public class Check {public static void main(String[] x) {'
                'System.out.print(Submission.submit(new Order("VIP",3,false)));}}')
            build = Path(directory) / 'classes'
            subprocess.run(['javac', '-d', str(build), *map(str, root.glob('*.java'))], check=True, capture_output=True)
            result = subprocess.check_output(['java', '-cp', str(build), 'Check'], text=True)
            self.assertEqual(result, 'ALLOW')

    def test_repair_rejects_wrong_scope_missing_fix_and_broken_other_cases(self):
        answer = '{"changed_file":"CouponPolicy.java","status":"fixed"}'
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'source'
            create_task('java_repair', root)
            self.assertFalse(evaluate('java_repair', root, answer)['passed'])
            target = root / 'CouponPolicy.java'
            original = target.read_text()
            target.write_text(original.replace('subtotal <= minimum', 'subtotal < minimum'))
            self.assertTrue(evaluate('java_repair', root, answer)['passed'])
            self.assertFalse((root / 'ExternalProbe.java').exists())
            target.write_text(target.read_text().replace('(long) subtotal', 'subtotal'))
            self.assertFalse(evaluate('java_repair', root, answer)['passed'])
            target.write_text(original.replace('subtotal <= minimum', 'subtotal < minimum'))
            (root / 'Order.java').write_text('class Order {}')
            self.assertFalse(evaluate('java_repair', root, answer)['passed'])


if __name__ == '__main__':
    unittest.main()
