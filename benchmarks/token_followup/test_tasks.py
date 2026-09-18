import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from benchmarks.token_followup.tasks import (
    JAVA_PREFIX, REPAIR_FILE, TASK_IDS, contents, create_task, evaluate, write,
)


ANSWERS = {
    'java_dispatch': {'outcome': 'REVIEW', 'destination': 'coast-manual', 'sink': 'ReviewLedger.recordDeferred'},
    'polyglot_retry': {'typescript_symbol': 'web/dispatch/retry.ts:resolve',
                       'python_symbol': 'workers/dispatch/retry.py:resolve',
                       'typescript_result': 'postponed', 'python_result': 'postponed'},
    'java_backoff': {'changed_file': REPAIR_FILE, 'status': 'fixed'},
}
BROKEN = 'return Math.min(capMillis, baseMillis * (1 << attempt));'
FIXED = '''long delay = Math.min(baseMillis, capMillis);
        for (int step = 0; step < attempt && delay < capMillis; step++)
            delay = Math.min(capMillis, delay * 2);
        return (int) delay;'''


class FreshTaskTests(unittest.TestCase):
    def make(self, task):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name) / 'workspace'
        create_task(task, root)
        return root

    def answer(self, task, root, answer=None):
        return evaluate(task, root, json.dumps(ANSWERS[task] if answer is None else answer))

    def fix(self, root, replacement=FIXED):
        target = root / REPAIR_FILE
        target.write_text(target.read_text().replace(BROKEN, replacement))

    def test_manifests_are_deterministic_and_only_task_data_is_generated(self):
        for task in TASK_IDS:
            with self.subTest(task=task), tempfile.TemporaryDirectory() as directory:
                a = create_task(task, Path(directory) / 'a')
                b = create_task(task, Path(directory) / 'b')
                self.assertEqual(a, b)
                sources = [name for name in a['workspace_files'] if name != 'TASK.md']
                self.assertGreaterEqual(len(sources), 40)
                self.assertLessEqual(len(sources), 100)
                self.assertTrue(all(name.endswith(('.java', '.ts', '.py')) for name in sources))
                self.assertFalse(any('ExternalProbe' in name or 'test_tasks' in name for name in sources))
                self.assertEqual(set(a['response_schema']['required']), set(ANSWERS[task]))
                with self.assertRaises(ValueError):
                    create_task(task, Path(directory) / 'a')

    def test_navigation_rejects_every_wrong_field_and_malformed_answers(self):
        for task in ('java_dispatch', 'polyglot_retry'):
            root = self.make(task)
            self.assertTrue(self.answer(task, root)['passed'])
            for field in ANSWERS[task]:
                with self.subTest(task=task, field=field):
                    bad = dict(ANSWERS[task], **{field: 'wrong'})
                    self.assertFalse(self.answer(task, root, bad)['passed'])
            for bad in ('[]', '{}', 'null', 'not JSON', json.dumps(dict(ANSWERS[task], extra='x'))):
                self.assertFalse(evaluate(task, root, bad)['passed'])

    def test_navigation_disallows_edits_missing_extra_and_symlinked_files(self):
        for mutation in ('edit', 'missing', 'extra', 'symlink'):
            with self.subTest(mutation=mutation):
                root = self.make('java_dispatch')
                target = root / (JAVA_PREFIX + 'ReviewLedger.java')
                if mutation == 'edit':
                    target.write_text(target.read_text().replace('REVIEW', 'RETRY'))
                elif mutation == 'missing':
                    target.unlink()
                elif mutation == 'extra':
                    (root / 'extra.txt').write_text('extra')
                else:
                    duplicate = root.parent / 'outside.java'
                    duplicate.write_bytes(target.read_bytes())
                    target.unlink()
                    target.symlink_to(duplicate)
                self.assertFalse(self.answer('java_dispatch', root)['passed'])

    def test_java_navigation_answers_match_execution_and_actual_sink(self):
        root = self.make('java_dispatch')
        with tempfile.TemporaryDirectory() as directory:
            copy = Path(directory) / 'copy'
            shutil.copytree(root, copy)
            sink = copy / (JAVA_PREFIX + 'ReviewLedger.java')
            sink.write_text(sink.read_text().replace(
                'return new DispatchDecision("REVIEW",',
                'System.out.print("ReviewLedger.recordDeferred|"); return new DispatchDecision("REVIEW",'))
            write(copy, 'Probe.java', '''
                import relay.*;
                public class Probe {
                    public static void main(String[] args) {
                        DispatchDecision decision = DispatchService.dispatch(new DispatchRequest("zone-b", 11, true, false));
                        System.out.print(decision.outcome() + "|" + decision.destination());
                    }
                }
            ''')
            build = Path(directory) / 'classes'
            build.mkdir()
            subprocess.run(['javac', '-d', str(build), *map(str, copy.rglob('*.java'))],
                           check=True, capture_output=True, timeout=45)
            output = subprocess.check_output(['java', '-cp', str(build), 'Probe'], text=True, timeout=10)
            expected = ANSWERS['java_dispatch']
            self.assertEqual(output, expected['sink'] + '|' + expected['outcome'] + '|' + expected['destination'])

    def test_polyglot_outputs_and_branch_precedence_match_real_execution(self):
        root = self.make('polyglot_retry')
        py_path = root / 'workers/dispatch/retry.py'
        spec = importlib.util.spec_from_file_location('fresh_retry_fixture', py_path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        cases = [(3, False, 200, 'postponed'), (3, False, 0, 'retry'),
                 (4, False, 200, 'exhausted'), (4, True, 200, 'cancelled')]
        for attempt, cancelled, delay, expected in cases:
            self.assertEqual(module.resolve(attempt, cancelled, delay), expected)
        ts_path = root / 'web/dispatch/retry.ts'
        script = ('const m = await import(' + json.dumps(ts_path.as_uri()) + ');'
                  'const cases = ' + json.dumps(cases) + ';'
                  'console.log(JSON.stringify(cases.map(c => m.resolve(c[0], c[1], c[2]))));')
        result = subprocess.check_output(['node', '--input-type=module', '-e', script], text=True, timeout=10)
        self.assertEqual(json.loads(result), [case[3] for case in cases])

    def test_repair_accepts_behaviorally_correct_fix_and_leaves_workspace_untouched(self):
        root = self.make('java_backoff')
        self.fix(root)
        write(root, '.scratch/probe/Probe.java', 'this is a deliberately invalid discarded probe')
        write(root, '.scratch/classes/stale.class', 'arbitrary build output')
        before = contents(root)
        result = self.answer('java_backoff', root)
        self.assertTrue(result['passed'], result)
        self.assertEqual(result['compile_exit'], 0)
        self.assertEqual(result['test_exit'], 0)
        self.assertEqual(contents(root), before)
        self.assertTrue((root / '.scratch/probe/Probe.java').exists())

    def test_repair_rejects_no_change_overflow_shift_wrap_and_invalid_input_regressions(self):
        root = self.make('java_backoff')
        self.assertFalse(self.answer('java_backoff', root)['passed'])
        mutants = {
            'shift_wrap': 'return (int) Math.min(capMillis, (long) baseMillis * (1 << attempt));',
            'long_overflow': 'return (int) Math.min(capMillis, (long) baseMillis << attempt);',
            'wrong_cap': FIXED.replace('Math.min(capMillis, delay * 2)', 'delay * 2'),
            'invalid_inputs': FIXED,
            'deadline_overflow': FIXED,
        }
        for mutation, replacement in mutants.items():
            with self.subTest(mutation=mutation):
                root = self.make('java_backoff')
                self.fix(root, replacement)
                target = root / REPAIR_FILE
                if mutation == 'invalid_inputs':
                    target.write_text(target.read_text().replace('throw new IllegalArgumentException("invalid backoff inputs");', 'return 0;'))
                if mutation == 'deadline_overflow':
                    target.write_text(target.read_text().replace('Math.addExact(nowMillis, delayMillis(attempt, baseMillis, capMillis))',
                                                                 'nowMillis + delayMillis(attempt, baseMillis, capMillis)'))
                self.assertFalse(self.answer('java_backoff', root)['passed'])

    def test_repair_rejects_wrong_scope_and_false_success_report(self):
        root = self.make('java_backoff')
        self.fix(root)
        for field in ANSWERS['java_backoff']:
            self.assertFalse(self.answer('java_backoff', root, dict(ANSWERS['java_backoff'], **{field: 'wrong'}))['passed'])
        other = root / (JAVA_PREFIX + 'RetryScheduler.java')
        other.write_text(other.read_text() + '\n')
        self.assertFalse(self.answer('java_backoff', root)['passed'])


if __name__ == '__main__':
    unittest.main()
