import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from benchmarks.token_interface.tasks import (
    JAVA_PREFIX, TARGETS, TASK_IDS, body_span, contents, create_task, evaluate, manifest,
)


SHORT_FIXES = (
    '''
        if (start < 0 || start >= 1440 || end < 0 || end >= 1440 || minute < 0 || minute >= 1440)
            throw new IllegalArgumentException("minute outside day");
        if (start == end) return false;
        return start < end ? start <= minute && minute < end : start <= minute || minute < end;
    ''',
    '''
        if (start < 0 || start >= 1440 || end < 0 || end >= 1440 || minute < 0 || minute >= 1440)
            throw new IllegalArgumentException("minute outside day");
        return Math.floorMod(minute - start, 1440) < Math.floorMod(end - start, 1440);
    ''',
)


class InterfaceTaskTests(unittest.TestCase):
    def make(self, task):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name) / 'workspace'
        create_task(task, root)
        return root

    def target(self, task, root):
        return root / (JAVA_PREFIX + TARGETS[task][0])

    def body(self, task, root):
        source = self.target(task, root).read_bytes()
        begin, end = body_span(source, TARGETS[task][1])
        return source[begin:end].decode()

    def replace_body(self, task, root, body):
        target = self.target(task, root)
        source = target.read_bytes()
        begin, end = body_span(source, TARGETS[task][1])
        target.write_bytes(source[:begin] + body.encode() + source[end:])

    def fix(self, task, root, variant=0):
        if task == 'java_short':
            body = SHORT_FIXES[variant]
        elif task == 'java_long':
            body = self.body(task, root).replace('result.append(notice.message());',
                'result.append(NoticeEscaper.escape(notice.message()));' if variant == 0 else
                'String message = NoticeEscaper.escape(notice.message());\n        result.append(message);')
        else:
            body = self.body(task, root).replace('return permits(granted, request.actionCode());',
                'return permits(granted, ActionCodes.requiredMask(request.actionCode()));' if variant == 0 else
                'int required = ActionCodes.requiredMask(request.actionCode());\n        return required != 0 && (granted & required) == required;')
        self.replace_body(task, root, body)

    def grade(self, task, root, answer=None):
        if answer is None:
            answer = {'changed_file': JAVA_PREFIX + TARGETS[task][0], 'status': 'fixed'}
        return evaluate(task, root, json.dumps(answer))

    def test_deterministic_fixture_manifests_and_no_evaluator_exposure(self):
        for task in TASK_IDS:
            with self.subTest(task=task), tempfile.TemporaryDirectory() as directory:
                first, second = Path(directory) / 'a', Path(directory) / 'b'
                first_spec, second_spec = create_task(task, first), create_task(task, second)
                self.assertEqual(first_spec, second_spec)
                self.assertEqual(first_spec['manifest_sha256'], manifest(first))
                self.assertEqual(len([path for path in first_spec['workspace_files'] if path.endswith('.java')]), 24)
                self.assertEqual(set(first_spec['response_schema']['required']), {'changed_file', 'status'})
                self.assertTrue(first_spec['writable'])
                self.assertFalse(any('ExternalProbe' in path or 'test_tasks' in path for path in first_spec['workspace_files']))
                self.assertTrue(all(path.endswith('.java') or path == 'TASK.md' for path in first_spec['workspace_files']))
                self.assertLess(len(self.body(task, first)), 4096)
                with self.assertRaises(ValueError):
                    create_task(task, first)

    def test_unchanged_and_noop_changed_baselines_fail(self):
        for task in TASK_IDS:
            with self.subTest(task=task):
                root = self.make(task)
                result = self.grade(task, root)
                self.assertFalse(result['passed'])
                self.assertEqual(result['details'], 'wrong change scope')
                self.replace_body(task, root, self.body(task, root) + '\n')
                result = self.grade(task, root)
                self.assertFalse(result['passed'], result)
                self.assertEqual(result['compile_exit'], 0, result)
                self.assertNotEqual(result['test_exit'], 0, result)

    def test_two_distinct_correct_implementations_per_task_pass(self):
        for task in TASK_IDS:
            for variant in (0, 1):
                with self.subTest(task=task, variant=variant):
                    root = self.make(task)
                    self.fix(task, root, variant)
                    before = contents(root)
                    result = self.grade(task, root)
                    self.assertTrue(result['passed'], result)
                    self.assertEqual(result['compile_exit'], 0)
                    self.assertEqual(result['test_exit'], 0)
                    self.assertEqual(contents(root), before)
                    self.assertLess(len(self.body(task, root)), 4096)

    def test_repeated_success_and_compile_failure_receipts_are_stable(self):
        for mutation in ('correct', 'invalid source'):
            with self.subTest(mutation=mutation):
                root = self.make('java_short')
                self.replace_body('java_short', root, SHORT_FIXES[0] if mutation == 'correct' else '\nnot valid Java;\n')
                first = self.grade('java_short', root)
                second = self.grade('java_short', root)
                self.assertEqual(first, second)
                self.assertEqual(first['passed'], mutation == 'correct')
                if mutation != 'correct':
                    self.assertNotEqual(first['compile_exit'], 0)

    def test_short_task_rejects_boundary_empty_window_and_invalid_input_mutants(self):
        mutants = (
            SHORT_FIXES[0].replace('start == end) return false', 'start == end) return true'),
            SHORT_FIXES[0].replace('minute < end', 'minute <= end'),
            SHORT_FIXES[0].replace('start <= minute', 'start < minute'),
            SHORT_FIXES[0].replace('start <= minute || minute < end', 'start <= minute && minute < end'),
            SHORT_FIXES[0].replace('throw new IllegalArgumentException("minute outside day");', 'return false;'),
            'return true;',
        )
        for index, body in enumerate(mutants):
            with self.subTest(mutant=index):
                root = self.make('java_short')
                self.replace_body('java_short', root, body)
                result = self.grade('java_short', root)
                self.assertFalse(result['passed'], result)
                self.assertEqual(result['compile_exit'], 0, result)

    def test_long_task_rejects_partial_double_escape_and_validation_regressions(self):
        replacements = (
            'result.append(notice.message().replace("|", "\\\\|"));',
            'result.append(NoticeEscaper.escape(NoticeEscaper.escape(notice.message())));',
            'result.append(NoticeEscaper.escape(notice.message().trim()));',
        )
        for replacement in replacements:
            with self.subTest(replacement=replacement):
                root = self.make('java_long')
                body = self.body('java_long', root).replace('result.append(notice.message());', replacement)
                self.replace_body('java_long', root, body)
                result = self.grade('java_long', root)
                self.assertFalse(result['passed'], result)
                self.assertEqual(result['compile_exit'], 0, result)
        root = self.make('java_long')
        self.fix('java_long', root)
        body = self.body('java_long', root).replace('options.maxLabels() < 0 || options.maxLabels() > 5', 'options.maxLabels() < 0')
        self.replace_body('java_long', root, body)
        result = self.grade('java_long', root)
        self.assertFalse(result['passed'], result)
        self.assertEqual(result['compile_exit'], 0, result)

    def test_overloaded_task_rejects_partial_permission_and_ignored_restrictions(self):
        for kind in ('any permission', 'service bypass', 'archive bypass', 'default table', 'all allowed'):
            with self.subTest(kind=kind):
                root = self.make('java_overload')
                self.fix('java_overload', root, 1)
                body = self.body('java_overload', root)
                if kind == 'any permission':
                    body = body.replace('(granted & required) == required', '(granted & required) != 0')
                elif kind == 'service bypass':
                    body = body.replace('request.serviceAccount()', 'false')
                elif kind == 'archive bypass':
                    body = body.replace('request.archived()', 'false')
                elif kind == 'default table':
                    body = body.replace('table.grantedMask(', 'PermissionTable.defaults().grantedMask(')
                else:
                    body = 'return true;'
                self.replace_body('java_overload', root, body)
                result = self.grade('java_overload', root)
                self.assertFalse(result['passed'], result)
                self.assertEqual(result['compile_exit'], 0, result)

    def test_body_scope_protects_other_overloads_signatures_and_class_content(self):
        for mutation in ('overload', 'signature', 'new method', 'other file'):
            with self.subTest(mutation=mutation):
                root = self.make('java_overload')
                self.fix('java_overload', root)
                target = self.target('java_overload', root)
                if mutation == 'overload':
                    target.write_text(target.read_text().replace('requiredMask <= 0', 'requiredMask < 0'))
                elif mutation == 'signature':
                    target.write_text(target.read_text().replace('public static boolean permits(AccessRequest', 'static boolean permits(AccessRequest'))
                elif mutation == 'new method':
                    self.replace_body('java_overload', root, self.body('java_overload', root) + '\n}\npublic static void inserted() {\n')
                else:
                    other = root / (JAVA_PREFIX + 'ActionCodes.java')
                    other.write_text(other.read_text() + '\n')
                self.assertFalse(self.grade('java_overload', root)['passed'])

    def test_workspace_scope_rejects_missing_extra_and_symlinked_files(self):
        for mutation in ('missing', 'extra', 'symlink', 'scratch symlink'):
            with self.subTest(mutation=mutation):
                root = self.make('java_short')
                self.fix('java_short', root)
                target = root / (JAVA_PREFIX + 'Room.java')
                if mutation == 'missing':
                    target.unlink()
                elif mutation == 'extra':
                    (root / 'extra.txt').write_text('extra')
                elif mutation == 'symlink':
                    outside = root.parent / 'outside.java'
                    outside.write_bytes(target.read_bytes())
                    target.unlink()
                    target.symlink_to(outside)
                else:
                    (root / '.scratch').symlink_to(root.parent, target_is_directory=True)
                self.assertFalse(self.grade('java_short', root)['passed'])

    def test_scratch_files_are_allowed_but_never_compiled_or_changed_by_evaluator(self):
        root = self.make('java_short')
        self.fix('java_short', root)
        scratch = root / '.scratch' / 'Probe.java'
        scratch.parent.mkdir()
        scratch.write_text('deliberately invalid discarded probe')
        marker = root / '.scratch' / 'marker.class'
        marker.write_bytes(b'not a real class')
        before = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                  for p in root.rglob('*') if p.is_file()}
        result = self.grade('java_short', root)
        self.assertTrue(result['passed'], result)
        after = {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                 for p in root.rglob('*') if p.is_file()}
        self.assertEqual(before, after)

    def test_answer_shape_and_claim_do_not_override_behavior(self):
        root = self.make('java_short')
        self.fix('java_short', root)
        for answer in ('null', '[]', '{}', 'not JSON', '{"status":"fixed","changed_file":false}',
                       json.dumps({'status': 'fixed', 'changed_file': JAVA_PREFIX + TARGETS['java_short'][0], 'extra': 'x'})):
            self.assertFalse(evaluate('java_short', root, answer)['passed'])
        self.assertFalse(self.grade('java_short', root, {'changed_file': 'wrong.java', 'status': 'fixed'})['passed'])
        self.assertFalse(self.grade('java_short', root, {'changed_file': JAVA_PREFIX + TARGETS['java_short'][0], 'status': 'pending'})['passed'])

    def test_completion_spoof_without_behavior_checks_is_rejected(self):
        root = self.make('java_short')
        self.replace_body('java_short', root, 'System.out.print("22194"); System.exit(0); return true;')
        result = self.grade('java_short', root)
        self.assertFalse(result['passed'], result)
        self.assertEqual(result['compile_exit'], 0)
        self.assertEqual(result['test_exit'], 0)

    def test_compiler_scope_handles_braces_unicode_and_rejects_unicode_escape_bypass(self):
        root = self.make('java_short')
        body = SHORT_FIXES[1].replace('return Math.floorMod',
            'String decoration = "} { 🪕"; char close = \'}\'; /* } */ // {\n        return Math.floorMod')
        self.replace_body('java_short', root, body)
        self.assertTrue(self.grade('java_short', root)['passed'])
        root = self.make('java_short')
        self.replace_body('java_short', root, SHORT_FIXES[1] + '\n\\u007d public static void inserted() {\n')
        result = self.grade('java_short', root)
        self.assertFalse(result['passed'])
        self.assertFalse(result['scope_valid'])
        root = self.make('java_short')
        self.replace_body('java_short', root, SHORT_FIXES[1].replace('return Math.floorMod', 'String close = \"\\u007d\"; return Math.floorMod'))
        self.assertTrue(self.grade('java_short', root)['passed'])


if __name__ == '__main__':
    unittest.main()
