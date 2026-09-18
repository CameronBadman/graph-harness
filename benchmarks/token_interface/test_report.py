from contextlib import contextmanager
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from benchmarks.token_interface import report


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + '\n')


def fixture(task, root):
    root.mkdir(parents=True)
    (root / 'Fixture.java').write_text('class Fixture {}\n')
    prompt = 'Complete independent unit-fixture task ' + task
    (root / 'TASK.md').write_text(prompt + '\n')
    return {'task_id': task, 'prompt': prompt,
            'response_schema': {'type': 'object', 'properties': {'ok': {'type': 'integer'}},
                                'required': ['ok'], 'additionalProperties': False},
            'writable': True, 'workspace_files': ['Fixture.java', 'TASK.md'],
            'manifest_sha256': report.trials.tasks.manifest(root), 'source_bytes': 17, 'source_lines': 1}


def evaluate(task, root, answer):
    try:
        passed = json.loads(answer) == {'ok': 1} and (root / 'Fixture.java').read_text() == 'class Fixture {}\n'
    except ValueError:
        passed = False
    return {'passed': passed, 'details': 'independent unit-test evaluator'}


def command(frozen, row, folder):
    workspace = folder / 'workspace'
    result = [frozen['codex_executable'], 'exec', *frozen['flags'], '--model', frozen['model'], '--sandbox',
              'workspace-write',
              '--cd', str(workspace), '--output-schema', str(folder / 'schema.json'),
              '--output-last-message', str(folder / 'answer.json')]
    if row['arm'] != 'native':
        args = report.trials.bridge_args(workspace, row['arm'])
        result += ['-c', 'mcp_servers.graphharness.command=' + json.dumps(frozen['launchers'][row['arm']]),
                   '-c', 'mcp_servers.graphharness.args=' + json.dumps(args),
                   '-c', 'mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=' + json.dumps(str(folder / 'runtime')),
                   '-c', 'mcp_servers.graphharness.required=true',
                   '-c', 'mcp_servers.graphharness.default_tools_approval_mode="approve"']
    return result + ['-']


@contextmanager
def dataset():
    with tempfile.TemporaryDirectory() as temporary:
        base = Path(temporary)
        identity = {'benchmarks/token_interface/tasks.py': report.digest(Path(report.trials.tasks.__file__).read_bytes()),
                    'product/artifact': 'registered product hash'}
        with patch.object(report.trials, 'identity', return_value=identity) as identity_mock, \
                patch.object(report.trials, 'calibration_behavior', side_effect=lambda root: 'return 38;' in (root / 'Gauge.java').read_text()), \
                patch.object(report.trials.tasks, 'create_task', side_effect=fixture), \
                patch.object(report.trials.tasks, 'evaluate', side_effect=evaluate) as evaluator:
            tasks = {}
            for task in report.trials.tasks.TASK_IDS:
                spec = fixture(task, base / 'specs' / task)
                spec['prompt'] = report.trials.COMMON + spec['prompt']
                tasks[task] = spec
            frozen = {'schema_version': 1, 'model': 'configured-test-model', 'model_identity': 'not attested',
                      'codex_version': 'test-client', 'codex_executable': '/private/native/codex',
                      'repository_head': 'unit-fixture', 'flags': ['--json'], 'schedule_seed': 2026091804,
                      'schedule': report._schedule(), 'tasks': tasks, 'artifact_hashes': identity,
                      'launchers': {arm: '/private/product/' + arm for arm in report.ARMS[1:]},
                      'integration_guidance': 'Discover GraphHarness, then retrieve relevant source.'}
            write_json(base / 'frozen.json', frozen)
            rows, calibrated, reviews = [], [], {}
            for registered in frozen['schedule'] + [{'id': 'calibration-' + arm, 'arm': arm,
                                                       'task': 'calibration', 'repetition': 0} for arm in report.ARMS]:
                row = dict(registered)
                folder = base / row['id']
                folder.mkdir()
                calibration = row['task'] == 'calibration'
                if calibration:
                    (folder / 'workspace').mkdir()
                    (folder / 'workspace' / 'Gauge.java').write_text('public class Gauge { public int reading() { return 37; } }\n')
                    calibration_spec = report.trials.calibration_spec(folder / 'workspace', row['arm'], frozen['integration_guidance'])
                    (folder / 'workspace' / 'Gauge.java').write_text('public class Gauge { public int reading() { return 38; } }\n')
                    answer = {'reading': 38}
                    prompt = calibration_spec['prompt']
                    write_json(folder / 'schema.json', calibration_spec['response_schema'])
                    write_json(folder / 'command.json', command(frozen, row, folder))
                else:
                    fixture(row['task'], folder / 'workspace')
                    answer = {'ok': 1}
                    prompt = report._actual_prompt(frozen, row['task'], row['arm'])
                    write_json(folder / 'schema.json', tasks[row['task']]['response_schema'])
                    write_json(folder / 'command.json', command(frozen, row, folder))
                (folder / 'prompt.txt').write_text(prompt)
                write_json(folder / 'answer.json', answer)
                (folder / 'stderr.log').write_text('unit-test stderr\n')
                inputs, outputs = {'native': (1000, 100), 'compact': (1400, 120),
                                   'node': (750, 95), 'slim': (700, 90), 'combined': (650, 85)}[row['arm']]
                retrieval = 'build_context_bundle'
                item = ({'id': 'one', 'type': 'command_execution', 'aggregated_output': '', 'exit_code': 0}
                        if row['arm'] == 'native' else
                        {'id': 'one', 'type': 'mcp_tool_call', 'tool': retrieval, 'result': {'structured_content': {'format': 'source-v1'} if row['arm'] in report.trials.SLIM_ARMS else {'source_slices': []}}})
                events = [{'type': 'item.completed', 'item': item}]
                if row['arm'] in report.trials.NODE_ARMS:
                    events.append({'type': 'item.completed', 'item': {'id': 'edit', 'type': 'mcp_tool_call', 'tool': 'replace_node_body',
                        'result': {'structured_content': {'committed': True}}}})
                events += [{'type': 'turn.completed', 'usage': {
                    'input_tokens': inputs, 'cached_input_tokens': inputs // 2,
                    'output_tokens': outputs, 'reasoning_output_tokens': 20}}]
                (folder / 'events.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
                diagnostic = report.trials.diagnostics(events)
                diagnostic['stderr_policy_rejections'] = 0
                row.update(usage=report.trials.support.parse_usage(events), correctness={'passed': True} if calibration else evaluate(
                    row['task'], folder / 'workspace', json.dumps(answer)),
                    instrumentation_valid=True, valid_measurement=False, infrastructure_failure=False,
                    timed_out=False, exit_code=0, elapsed_seconds=1.0, setup_seconds=0.1,
                    diagnostics=diagnostic, frozen_sha256=report.digest((base / 'frozen.json').read_bytes()),
                    evaluator_sha256=identity['benchmarks/token_interface/tasks.py'],
                    prompt_sha256=report.digest(prompt.encode()),
                    raw_events_sha256=report.digest((folder / 'events.jsonl').read_bytes()),
                    stderr_sha256=report.digest((folder / 'stderr.log').read_bytes()),
                    answer_sha256=report.digest((folder / 'answer.json').read_bytes()))
                if calibration:
                    row.update(fixture_sha256=calibration_spec['manifest_sha256'], workspace_before_sha256=calibration_spec['manifest_sha256'],
                               workspace_after_sha256=report.trials.tasks.manifest(folder / 'workspace'),
                               schema_sha256=report.digest(report.stable(calibration_spec['response_schema'])))
                if not calibration:
                    manifest = tasks[row['task']]['manifest_sha256']
                    row.update(fixture_sha256=manifest, workspace_before_sha256=manifest, workspace_after_sha256=manifest,
                               schema_sha256=report.digest(report.stable(tasks[row['task']]['response_schema'])))
                definitions = [] if row['arm'] == 'native' else [{'name': name} for name in sorted(report.trials.expected_tools_for(row['arm']))]
                row.update(tools=[value['name'] for value in definitions],
                           tool_schema_sha256=report.digest(report.stable(definitions)),
                           tool_schema_json_bytes=0 if row['arm'] == 'native' else len(report.stable(definitions)))
                if row['arm'] != 'native':
                    backend = {'analysis_engine': 'unit-parser', 'engine_version': 'unit-1', 'languages': ['java'],
                               'language_adapters': {'java': {'language': 'java', 'parser': 'unit-parser',
                                   'version': 'unit-1', 'available': True, 'capabilities': ['source'],
                                   'diagnostics': ['private diagnostic at /private/runtime/path']}},
                               'semantic_level': 'structural'}
                    row['backend'] = backend
                    capabilities = dict(backend, coordinated_writes=True)
                    write_json(folder / 'capabilities.json', capabilities)
                    row['capabilities_sha256'] = report.digest(report.stable(capabilities))
                    write_json(folder / 'daemon-command.json', [frozen['launchers'][row['arm']], 'daemon', str(folder / 'workspace'), '--allow-edits'])
                    contract = {'initialize': {'protocolVersion': '2025-06-18'}, 'tools': definitions}
                    write_json(folder / 'mcp-contract.json', contract)
                    write_json(folder / 'tool-definitions.json', definitions)
                    row['mcp_contract_sha256'] = report.digest(report.stable(contract))
                    row['admission'] = {'expected_source_files': 1, 'indexed_source_files': 1}
                if not calibration:
                    rows.append(row)
                else:
                    calibrated.append(row)
                reviews[row['id']] = {'passed': True, 'raw_events_sha256': row['raw_events_sha256'],
                                      'stderr_sha256': row['stderr_sha256'], 'auditor': 'unit-test reviewer',
                                      'method': 'test judgment containing /private/workspace/path', 'violations': []}
                write_json(folder / 'record.json', row)
            write_json(base / 'results.json', rows)
            write_json(base / 'calibration.json', calibrated)
            write_json(base / 'access-review.json', reviews)
            yield base, frozen, rows, reviews, identity_mock, evaluator


def save_row(base, rows, row):
    write_json(base / row['id'] / 'record.json', row)
    write_json(base / 'results.json', rows)


class FreshReportTests(unittest.TestCase):
    def test_factorial_contrasts_hold_the_other_factor_fixed(self):
        with dataset() as (base, frozen, _, _, _, _):
            _, rows = report.collect(base)
            summary = report.summarize(rows, frozen)
            pairs = {(row['baseline'], row['treatment']): row for row in summary['comparisons']}
            self.assertEqual(set(pairs), {('native', arm) for arm in report.ARMS[1:]} |
                             {('compact', 'slim'), ('node', 'combined'), ('compact', 'node'), ('slim', 'combined')})
            self.assertAlmostEqual(pairs['node', 'combined']['percent_decrease']['input_tokens'], 100 / 7.5)
            self.assertEqual(pairs['compact', 'slim']['percent_decrease']['input_tokens'], 50)

    def test_savings_without_actual_node_or_format_use_cannot_pass(self):
        with dataset() as (base, frozen, _, _, _, _):
            _, rows = report.collect(base)
            for row in rows:
                if row['arm'] == 'node':
                    row['diagnostics']['node_write_commits'] = 0
                if row['arm'] == 'slim':
                    row['diagnostics']['source_v1_bundles'] = 0
            threshold = report.summarize(rows, frozen)['continuation_threshold']
            self.assertFalse(threshold['node']['passed'])
            self.assertFalse(threshold['slim']['passed'])
            self.assertTrue(threshold['combined']['passed'])

    def test_calibration_prompt_schema_and_write_capability_are_bound(self):
        for mutation in ('prompt', 'schema', 'capability', 'daemon_command', 'workspace'):
            with self.subTest(mutation=mutation), dataset() as (base, _, _, _, _, _):
                folder = base / 'calibration-combined'
                if mutation == 'prompt':
                    (folder / 'prompt.txt').write_text('Different guidance')
                elif mutation == 'schema':
                    write_json(folder / 'schema.json', {'type': 'object'})
                elif mutation == 'capability':
                    value = json.loads((folder / 'capabilities.json').read_text())
                    value['coordinated_writes'] = False
                    write_json(folder / 'capabilities.json', value)
                elif mutation == 'daemon_command':
                    command = json.loads((folder / 'daemon-command.json').read_text())
                    write_json(folder / 'daemon-command.json', command[:-1])
                else:
                    (folder / 'workspace' / 'Gauge.java').write_text('class Gauge {}')
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_source_overlap_counts_only_observable_later_output(self):
        events = [
            {'type': 'item.completed', 'item': {'id': 'read', 'type': 'mcp_tool_call', 'tool': 'build_context_bundle',
                'result': {'structured_content': {'format': 'source-v1', 'nodes': [{'sources': [{'source': 'return value + 1;\n}'}]}]}}}},
            {'type': 'item.completed', 'item': {'id': 'shell', 'type': 'command_execution', 'exit_code': 0,
                'aggregated_output': 'src/F.java:10:return value + 1;'}},
            {'type': 'item.completed', 'item': {'id': 'write', 'type': 'mcp_tool_call', 'tool': 'replace_node_body',
                'result': {'structured_content': {'committed': False}}}},
        ]
        value = report.trials.diagnostics(events)
        self.assertEqual(value['source_v1_bundles'], 1)
        self.assertEqual(value['source_lines_repeated_in_later_shell'], 1)
        self.assertEqual(value['node_write_calls'], 1)
        self.assertEqual(value['node_write_commits'], 0)

    def test_recomputes_all_scored_outcomes_and_correct_percentage_denominators(self):
        with dataset() as (base, frozen, _, _, _, evaluator):
            verified, rows = report.collect(base)
            summary = report.summarize(rows, verified)
            self.assertEqual(evaluator.call_count, 30)
            self.assertEqual(len(summary['common_cells']), 6)
            repaired = next(item for item in summary['comparisons'] if item['baseline'] == 'native' and item['treatment'] == 'node')
            self.assertEqual(repaired['percent_decrease']['input_tokens'], 25)
            self.assertEqual(repaired['percent_decrease']['output_tokens'], 5)
            legacy = next(item for item in summary['comparisons'] if item['baseline'] == 'native' and item['treatment'] == 'compact')
            self.assertEqual(legacy['percent_decrease']['input_tokens'], -40)
            self.assertTrue(summary['continuation_threshold']['node']['passed'])
            self.assertTrue(summary['continuation_threshold']['slim']['passed'])
            self.assertFalse(summary['continuation_threshold']['compact']['passed'])
            self.assertEqual(summary['arms']['native']['all_known_usage_totals']['total_tokens'], 6600)

    def test_identity_schedule_record_prompt_fixture_and_schema_mutations_fail(self):
        for mutation in ('identity', 'schedule', 'record', 'prompt', 'actual_prompt', 'fixture', 'schema', 'evaluator', 'command'):
            with self.subTest(mutation=mutation), dataset() as (base, frozen, rows, _, identity, _):
                row = next(row for row in rows if row['arm'] == 'node')
                folder = base / row['id']
                if mutation == 'identity':
                    identity.return_value = dict(frozen['artifact_hashes'], changed='yes')
                elif mutation == 'schedule':
                    frozen['schedule'] = frozen['schedule'][:-1]
                    write_json(base / 'frozen.json', frozen)
                elif mutation == 'record':
                    altered = dict(row, repetition=99)
                    write_json(folder / 'record.json', altered)
                elif mutation == 'prompt':
                    row['prompt_sha256'] = report.digest(frozen['tasks'][row['task']]['prompt'].encode())
                    save_row(base, rows, row)
                elif mutation == 'actual_prompt':
                    (folder / 'prompt.txt').write_text('different actual prompt')
                elif mutation == 'fixture':
                    row['workspace_before_sha256'] = 'different'
                    save_row(base, rows, row)
                elif mutation == 'schema':
                    write_json(folder / 'schema.json', {'type': 'object'})
                elif mutation == 'evaluator':
                    row['evaluator_sha256'] = 'different'
                    save_row(base, rows, row)
                else:
                    values = json.loads((folder / 'command.json').read_text())
                    values[0] = '/unregistered/codex'
                    write_json(folder / 'command.json', values)
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_rehashed_counter_and_diagnostic_tampering_is_detected(self):
        for mutation in ('usage', 'duplicate_counter', 'impossible_counter', 'diagnostics', 'stderr_diagnostic'):
            with self.subTest(mutation=mutation), dataset() as (base, _, rows, _, _, _):
                row = rows[0]
                folder = base / row['id']
                if mutation == 'usage':
                    row['usage']['input_tokens'] -= 10
                elif mutation == 'diagnostics':
                    row['diagnostics']['retrieval_used'] = not row['diagnostics']['retrieval_used']
                elif mutation == 'stderr_diagnostic':
                    (folder / 'stderr.log').write_text('Rejected(policy)\n')
                    row['stderr_sha256'] = report.digest((folder / 'stderr.log').read_bytes())
                else:
                    events = [json.loads(line) for line in (folder / 'events.jsonl').read_text().splitlines()]
                    if mutation == 'duplicate_counter':
                        events.append(copy.deepcopy(events[-1]))
                    else:
                        events[-1]['usage']['cached_input_tokens'] = 999999
                    (folder / 'events.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
                    row['raw_events_sha256'] = report.digest((folder / 'events.jsonl').read_bytes())
                save_row(base, rows, row)
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_missing_or_changed_raw_artifacts_cannot_be_replaced_by_review(self):
        for filename in ('events.jsonl', 'stderr.log', 'answer.json', 'prompt.txt'):
            with self.subTest(filename=filename), dataset() as (base, _, rows, _, _, _):
                (base / rows[0]['id'] / filename).unlink()
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_review_requires_both_hashes_judgment_identity_method_and_explicit_empty_violations(self):
        mutations = ['raw_events_sha256', 'stderr_sha256', 'auditor', 'method', 'violations', 'passed', 'pending', 'violation']
        for mutation in mutations:
            with self.subTest(mutation=mutation), dataset() as (base, frozen, rows, reviews, _, _):
                row = next(row for row in rows if row['arm'] == 'node')
                review = reviews[row['id']]
                if mutation == 'pending':
                    del reviews[row['id']]
                elif mutation == 'violation':
                    review['violations'] = ['outside-workspace access']
                elif mutation in ('passed',):
                    review[mutation] = False
                elif mutation in ('raw_events_sha256', 'stderr_sha256'):
                    review[mutation] = 'wrong'
                else:
                    del review[mutation]
                write_json(base / 'access-review.json', reviews)
                _, result = report.collect(base)
                self.assertEqual(len(result), 30)
                self.assertFalse(next(value for value in result if value['id'] == row['id'])['valid_measurement'])
                summary = report.summarize(result, frozen)
                self.assertEqual(summary['arms']['node']['attempts'], 6)
                self.assertEqual(summary['arms']['node']['valid'], 5)
                self.assertEqual(len(summary['common_cells']), 5)
                self.assertFalse(summary['continuation_threshold']['node']['passed'])

    def test_unequal_valid_counts_use_identical_common_cells_for_every_comparison(self):
        with dataset() as (base, frozen, rows, reviews, _, _):
            row = next(row for row in rows if row['arm'] == 'compact')
            reviews[row['id']]['passed'] = False
            write_json(base / 'access-review.json', reviews)
            _, verified = report.collect(base)
            summary = report.summarize(verified, frozen)
            self.assertEqual(summary['arms']['native']['valid_usage_totals']['input_tokens'], 6000)
            self.assertEqual(summary['arms']['compact']['valid_usage_totals']['input_tokens'], 7000)
            self.assertEqual(summary['arms']['native']['matched_usage_totals']['input_tokens'], 5000)
            self.assertEqual(summary['arms']['compact']['matched_usage_totals']['input_tokens'], 7000)
            comparison = next(value for value in summary['comparisons'] if value['baseline'] == 'native' and value['treatment'] == 'compact')
            self.assertEqual(comparison['percent_decrease']['input_tokens'], -40)
            self.assertTrue(all(value['matched_cells'] == 5 for value in summary['comparisons']))
            self.assertEqual(len(summary['pairs']), 40)

    def test_external_correctness_rerun_and_workspace_binding_detect_tampering(self):
        for mutation in ('claimed_correctness', 'workspace', 'forged_answer'):
            with self.subTest(mutation=mutation), dataset() as (base, _, rows, _, _, _):
                row = rows[0]
                folder = base / row['id']
                if mutation == 'claimed_correctness':
                    row['correctness']['passed'] = False
                elif mutation == 'workspace':
                    (folder / 'workspace' / 'Fixture.java').write_text('class Different {}\n')
                else:
                    write_json(folder / 'answer.json', {'ok': 0})
                    row['answer_sha256'] = report.digest((folder / 'answer.json').read_bytes())
                save_row(base, rows, row)
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_incorrect_attempt_is_retained_and_excluded_from_every_arm_denominator(self):
        with dataset() as (base, frozen, rows, _, _, _):
            row = next(row for row in rows if row['arm'] == 'slim')
            folder = base / row['id']
            write_json(folder / 'answer.json', {'ok': 0})
            row['answer_sha256'] = report.digest((folder / 'answer.json').read_bytes())
            row['correctness']['passed'] = False
            save_row(base, rows, row)
            _, verified = report.collect(base)
            summary = report.summarize(verified, frozen)
            self.assertEqual(summary['arms']['slim']['attempts'], 6)
            self.assertEqual(summary['arms']['slim']['correct_attempts'], 5)
            self.assertEqual(len(summary['common_cells']), 5)
            self.assertFalse(summary['continuation_threshold']['slim']['passed'])

    def test_captured_mcp_contract_and_admission_must_match_profile(self):
        for mutation in ('contract', 'admission', 'backend'):
            with self.subTest(mutation=mutation), dataset() as (base, _, rows, _, _, _):
                row = next(row for row in rows if row['arm'] == 'slim')
                if mutation == 'contract':
                    write_json(base / row['id'] / 'mcp-contract.json', {'initialize': {}, 'tools': []})
                elif mutation == 'backend':
                    path = base / row['id'] / 'capabilities.json'
                    value = json.loads(path.read_text())
                    value['engine_version'] = 'different-version'
                    write_json(path, value)
                else:
                    row['admission']['indexed_source_files'] = 0
                    save_row(base, rows, row)
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_missing_duplicate_attempt_and_corrupted_calibration_fail(self):
        for mutation in ('missing', 'duplicate', 'calibration'):
            with self.subTest(mutation=mutation), dataset() as (base, _, rows, _, _, _):
                if mutation == 'missing':
                    write_json(base / 'results.json', rows[:-1])
                elif mutation == 'duplicate':
                    write_json(base / 'results.json', rows + [rows[0]])
                else:
                    (base / 'calibration-native' / 'stderr.log').write_text('changed calibration stderr')
                with self.assertRaises(ValueError):
                    report.collect(base)

    def test_render_omits_local_paths_raw_answers_and_review_prose(self):
        with dataset() as (base, _, _, _, _, _):
            frozen, verified = report.collect(base)
            output = base / 'published'
            report.render(frozen, verified, report.summarize(verified, frozen), output)
            published = json.loads((output / 'runs.json').read_text())
            backend = next(row['backend'] for row in published if row['arm'] != 'native')
            self.assertEqual(backend['engine_version'], 'unit-1')
            self.assertEqual(backend['language_adapters']['java']['diagnostic_count'], 1)
            for path in output.iterdir():
                data = path.read_text()
                self.assertNotIn(str(base), data)
                self.assertNotIn('/private/', data)
                self.assertNotIn('test judgment containing', data)
                self.assertNotIn('"ok": 1', data)
            self.assertIn('negative values mean more tokens', (output / 'REPORT.md').read_text())

    def test_compile_failure_diagnostic_variation_keeps_incorrect_attempt(self):
        with dataset() as (base, frozen, rows, _, _, oracle):
            row = next(row for row in rows if row['task'] == 'java_long' and row['arm'] == 'node')
            row['correctness'] = {'passed': False, 'details': 'external compile failure', 'compile_exit': 1,
                                  'test_exit': None, 'changed_files': ['Fixture.java'],
                                  'test_output_sha256': report.digest(b'private temporary path one')}
            save_row(base, rows, row)
            def failed_compile(task, root, answer):
                if root.parent.name == row['id']:
                    return dict(row['correctness'], test_output_sha256=report.digest(b'private temporary path two'))
                return evaluate(task, root, answer)
            oracle.side_effect = failed_compile
            _, verified = report.collect(base)
            retained = next(value for value in verified if value['id'] == row['id'])
            self.assertFalse(retained['correctness']['passed'])
            self.assertTrue(retained['correctness_comparison']['diagnostic_hash_changed'])
            self.assertNotEqual(retained['correctness']['test_output_sha256'], retained['correctness_recheck']['test_output_sha256'])
            self.assertEqual(len(report.summarize(verified, frozen)['common_cells']), 5)
            for field, value in (('passed', True), ('compile_exit', 0), ('changed_files', [])):
                bad = dict(row['correctness'], **{field: value})
                with self.assertRaises(ValueError):
                    report._compare_correctness(row['correctness'], bad)

    def test_timeout_with_missing_answer_and_counters_is_retained_as_unknown(self):
        with dataset() as (base, frozen, rows, reviews, _, _):
            row = rows[0]
            folder = base / row['id']
            events = [json.loads(line) for line in (folder / 'events.jsonl').read_text().splitlines()]
            events = events[:-1]
            (folder / 'events.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
            (folder / 'answer.json').unlink()
            row.update(raw_events_sha256=report.digest((folder / 'events.jsonl').read_bytes()),
                       answer_sha256=report.digest(b''), usage=None, instrumentation_valid=False,
                       timed_out=True, exit_code=-15, correctness={'passed': False, 'details': 'independent unit-test evaluator'})
            save_row(base, rows, row)
            reviews[row['id']]['raw_events_sha256'] = row['raw_events_sha256']
            write_json(base / 'access-review.json', reviews)
            _, verified = report.collect(base)
            summary = report.summarize(verified, frozen)
            self.assertEqual(len(verified), 30)
            self.assertEqual(summary['arms'][row['arm']]['missing_usage'], 1)
            self.assertEqual(summary['arms'][row['arm']]['timeouts'], 1)
            self.assertEqual(len(summary['common_cells']), 5)

    def test_calibration_requires_bundle_route_for_repaired_and_navigation(self):
        for arm in ('node', 'slim'):
            with self.subTest(arm=arm), dataset() as (base, _, _, reviews, _, _):
                calibrated = json.loads((base / 'calibration.json').read_text())
                row = next(row for row in calibrated if row['arm'] == arm)
                folder = base / row['id']
                events = [json.loads(line) for line in (folder / 'events.jsonl').read_text().splitlines()]
                events[0]['item']['tool'] = 'get_source'
                (folder / 'events.jsonl').write_text(''.join(json.dumps(event) + '\n' for event in events))
                row['raw_events_sha256'] = report.digest((folder / 'events.jsonl').read_bytes())
                row['diagnostics'] = report.trials.diagnostics(events)
                row['diagnostics']['stderr_policy_rejections'] = 0
                write_json(folder / 'record.json', row)
                write_json(base / 'calibration.json', calibrated)
                reviews[row['id']]['raw_events_sha256'] = row['raw_events_sha256']
                write_json(base / 'access-review.json', reviews)
                with self.assertRaisesRegex(ValueError, 'calibration no longer passes'):
                    report.collect(base)

    def test_read_adoption_excludes_capability_and_write_calls_but_does_not_claim_usefulness(self):
        row = {'diagnostics': {'tool_calls': {'mcp:get_capabilities': 1, 'mcp:apply_edit': 1}, 'retrieval_used': True}}
        self.assertFalse(report._read_retrieval(row))
        row['diagnostics']['tool_calls']['mcp:build_context_bundle'] = 1
        row['diagnostics']['tool_errors'] = 1
        self.assertTrue(report._read_retrieval(row))


if __name__ == '__main__':
    unittest.main()
