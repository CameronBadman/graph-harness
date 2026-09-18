from __future__ import annotations

import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import random
import re
import statistics
import sys
import tempfile

if __package__ in (None, ''):
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from benchmarks.token_followup import run_trials as trials

ARMS = trials.ARMS
METRICS = ('input_tokens', 'cached_input_tokens', 'uncached_input_tokens', 'output_tokens',
           'reasoning_output_tokens', 'total_tokens')
COMPARISONS = [(baseline, arm) for baseline in ('native', 'legacy') for arm in ARMS if arm != baseline]
SOURCE_EXTENSIONS = {'.java', '.ts', '.tsx', '.js', '.jsx', '.py'}
BACKEND_FIELDS = ('analysis_engine', 'engine_version', 'languages', 'language_adapters', 'semantic_level')
READ_TOOLS = {'build_context_bundle', 'search_graph', 'get_source', 'get_source_batch',
              'get_summary_map', 'get_cluster_detail', 'get_node_detail', 'get_call_paths',
              'get_callers', 'get_callees', 'get_implementations', 'get_type_hierarchy',
              'get_dependencies', 'get_impact'}
digest, stable = trials.digest, trials.stable


def _load(path):
    try:
        return json.loads(path.read_bytes())
    except (FileNotFoundError, ValueError) as error:
        raise ValueError('required JSON evidence missing or malformed: ' + path.name) from error


def _schedule():
    rng = random.Random(2026091803)
    blocks = [(task, repetition) for task in trials.tasks.TASK_IDS for repetition in (1, 2)]
    rng.shuffle(blocks)
    result = []
    for task, repetition in blocks:
        arms = list(ARMS)
        rng.shuffle(arms)
        result += [{'id': f'{task}-{repetition}-{arm}', 'task': task,
                    'repetition': repetition, 'arm': arm} for arm in arms]
    return result


def _actual_prompt(frozen, task, arm):
    prompt = frozen['tasks'][task]['prompt']
    if arm in ('repaired', 'navigation'):
        prompt += '\n\n' + frozen['integration_guidance']
    return prompt


def _bound_bytes(folder, name, claimed, required=True):
    path = folder / name
    if not path.is_file():
        if claimed is not None or required:
            raise ValueError('raw evidence missing: ' + name)
        return None
    value = path.read_bytes()
    if claimed is not None and digest(value) != claimed:
        raise ValueError('raw evidence changed: ' + name)
    return value


def _command(frozen, row, folder):
    path = folder / 'command.json'
    if not path.is_file() and row.get('infrastructure_failure'):
        return False
    command = _load(path)
    if not isinstance(command, list) or not all(isinstance(value, str) for value in command):
        raise ValueError('invalid command evidence')
    try:
        source_workspace = Path(command[command.index('--cd') + 1])
    except (ValueError, IndexError) as error:
        raise ValueError('command workspace missing') from error
    source_folder = source_workspace.parent
    if source_workspace.name != 'workspace' or source_folder.name != row['id']:
        raise ValueError('command workspace is not the scheduled run')
    spec = frozen['tasks'][row['task']]
    expected = [frozen['codex_executable'], 'exec', *frozen['flags'], '--model', frozen['model'], '--sandbox',
                'workspace-write' if spec['writable'] else 'read-only', '--cd', str(source_workspace),
                '--output-schema', str(source_folder / 'schema.json'),
                '--output-last-message', str(source_folder / 'answer.json')]
    if row['arm'] != 'native':
        args = ['bridge', str(source_workspace), 'Token follow-up']
        if row['arm'] == 'navigation':
            args += ['--navigation']
        expected += ['-c', 'mcp_servers.graphharness.command=' + json.dumps(frozen['launchers'][row['arm']]),
                     '-c', 'mcp_servers.graphharness.args=' + json.dumps(args),
                     '-c', 'mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=' + json.dumps(str(source_folder / 'runtime')),
                     '-c', 'mcp_servers.graphharness.required=true',
                     '-c', 'mcp_servers.graphharness.default_tools_approval_mode="approve"']
    if command != expected + ['-']:
        raise ValueError('actual command differs from frozen configuration')
    return True


def _review(review, event_hash, stderr_hash):
    if not isinstance(review, dict):
        raise ValueError('access review must be an object')
    if not review:
        return {'status': 'pending', 'passed': False, 'violation_count': None}
    complete = (type(review.get('passed')) is bool
                and type(review.get('auditor')) is str and bool(review['auditor'].strip())
                and type(review.get('method')) is str and bool(review['method'].strip())
                and isinstance(review.get('violations'), list)
                and event_hash is not None and stderr_hash is not None
                and review.get('raw_events_sha256') == event_hash
                and review.get('stderr_sha256') == stderr_hash)
    passed = complete and review['passed'] is True and review['violations'] == []
    return {'status': 'passed' if passed else 'rejected' if complete else 'unbound',
            'passed': bool(passed),
            'violation_count': len(review['violations']) if isinstance(review.get('violations'), list) else None,
            'auditor_sha256': digest(review['auditor'].encode()) if isinstance(review.get('auditor'), str) else None,
            'method_sha256': digest(review['method'].encode()) if isinstance(review.get('method'), str) else None}


def _verify_calibration(base, frozen, reviews):
    calibrated = _load(base / 'calibration.json')
    if (not isinstance(calibrated, list) or len(calibrated) != len(ARMS)
            or {row.get('arm') for row in calibrated} != set(ARMS)):
        raise ValueError('calibration does not include each registered arm exactly once')
    for row in calibrated:
        if row.get('id') != 'calibration-' + row['arm'] or row.get('task') != 'calibration' or row.get('repetition') != 0:
            raise ValueError('calibration identity differs')
        folder = base / row['id']
        if _load(folder / 'record.json') != row:
            raise ValueError('calibration record differs from aggregate')
        if row.get('frozen_sha256') != digest((base / 'frozen.json').read_bytes()):
            raise ValueError('calibration not bound to frozen protocol')
        raw_events = _bound_bytes(folder, 'events.jsonl', row.get('raw_events_sha256'))
        stderr = _bound_bytes(folder, 'stderr.log', row.get('stderr_sha256'))
        answer = _bound_bytes(folder, 'answer.json', row.get('answer_sha256'))
        events = [json.loads(line) for line in raw_events.splitlines() if line.strip()]
        usage = trials.support.parse_usage(events)
        diagnostic = trials.support.diagnostics(events)
        diagnostic['stderr_policy_rejections'] = len(re.findall(r'Rejected\(', stderr.decode(errors='replace')))
        if usage != row.get('usage') or diagnostic != row.get('diagnostics'):
            raise ValueError('calibration usage or diagnostics differ from raw evidence')
        expected_call = ('shell' if row['arm'] == 'native' else 'mcp:get_source' if row['arm'] == 'legacy'
                         else 'mcp:build_context_bundle')
        expected_route = diagnostic['tool_calls'].get(expected_call, 0) > 0
        if (json.loads(answer) != {'reading': 37} or not expected_route
                or row.get('correctness') != {'passed': True} or row.get('instrumentation_valid') is not True
                or row.get('exit_code') != 0 or row.get('timed_out') is not False or row.get('infrastructure_failure')):
            raise ValueError('calibration no longer passes correctness, route and instrumentation')
        if not _review(reviews.get(row['id'], {}), digest(raw_events), digest(stderr))['passed']:
            raise ValueError('calibration lacks bound passing access review')


def _compare_correctness(recorded, checked, infrastructure_failure=False):
    if recorded == checked:
        return {'matched': True, 'comparison': 'all fields', 'diagnostic_hash_changed': False}
    if infrastructure_failure and recorded == {'passed': False} and checked.get('passed') is False:
        return {'matched': True, 'comparison': 'failed setup default and external rejection',
                'diagnostic_hash_changed': False}
    stable_recorded = {key: value for key, value in recorded.items() if key != 'test_output_sha256'}
    stable_checked = {key: value for key, value in checked.items() if key != 'test_output_sha256'}
    if (stable_recorded == stable_checked and recorded.get('passed') is False
            and type(recorded.get('compile_exit')) is int and recorded['compile_exit'] != 0
            and all(re.fullmatch(r'[0-9a-f]{64}', value or '')
                    for value in (recorded.get('test_output_sha256'), checked.get('test_output_sha256')))):
        return {'matched': True, 'comparison': 'semantic fields; failed javac diagnostics may contain temporary paths',
                'diagnostic_hash_changed': True}
    raise ValueError('repeated external correctness differs from recorded outcome')


def collect(base):
    base = Path(base)
    frozen_bytes = (base / 'frozen.json').read_bytes()
    frozen = _load(base / 'frozen.json')
    if frozen.get('schedule_seed') != 2026091803 or frozen.get('schedule') != _schedule():
        raise ValueError('frozen schedule differs from registered balanced schedule')
    if trials.identity(frozen['launchers']) != frozen['artifact_hashes']:
        raise ValueError('frozen product, runner, evaluator, protocol or host identity changed')
    evaluator_hash = digest(Path(trials.tasks.__file__).read_bytes())
    if frozen['artifact_hashes'].get('benchmarks/token_followup/tasks.py') != evaluator_hash:
        raise ValueError('evaluator is not bound to frozen artifacts')
    with tempfile.TemporaryDirectory(prefix='graphharness-report-fixtures-') as temporary:
        for task in trials.tasks.TASK_IDS:
            generated = trials.tasks.create_task(task, Path(temporary) / task)
            generated['prompt'] = trials.COMMON + generated['prompt']
            if frozen['tasks'].get(task) != generated:
                raise ValueError('registered fixture, prompt or schema differs from generator')
    raw_rows = _load(base / 'results.json')
    reviews = _load(base / 'access-review.json')
    if not isinstance(raw_rows, list) or not isinstance(reviews, dict):
        raise ValueError('results and access review have incorrect shapes')
    _verify_calibration(base, frozen, reviews)
    expected = {item['id']: item for item in frozen['schedule']}
    ids = [row.get('id') for row in raw_rows]
    if len(set(ids)) != len(ids):
        raise ValueError('duplicate run')
    if set(ids) != set(expected):
        raise ValueError('missing or unexpected scheduled attempts; no partial savings report')
    rows = []
    for raw in raw_rows:
        row = dict(raw)
        registered = expected[row['id']]
        if any(row.get(key) != registered[key] for key in ('id', 'task', 'arm', 'repetition')):
            raise ValueError('run metadata differs from schedule')
        folder = base / row['id']
        if _load(folder / 'record.json') != raw:
            raise ValueError('record.json differs from results.json')
        if row.get('frozen_sha256') != digest(frozen_bytes):
            raise ValueError('run not bound to frozen protocol')
        if row.get('evaluator_sha256') != evaluator_hash:
            raise ValueError('run evaluator differs from frozen evaluator')
        spec = frozen['tasks'][row['task']]
        prompt = _actual_prompt(frozen, row['task'], row['arm']).encode()
        if row.get('prompt_sha256') != digest(prompt):
            raise ValueError('prompt differs from registered task and arm guidance')
        if _bound_bytes(folder, 'prompt.txt', row['prompt_sha256']) != prompt:
            raise ValueError('actual prompt differs from registration')
        if (row.get('fixture_sha256') != spec['manifest_sha256']
                or row.get('workspace_before_sha256') != spec['manifest_sha256']):
            raise ValueError('fixture differs from registered corpus')
        schema = _load(folder / 'schema.json')
        if schema != spec['response_schema'] or row.get('schema_sha256') != digest(stable(schema)):
            raise ValueError('response schema differs from registration')
        command_present = _command(frozen, row, folder)
        raw_events = _bound_bytes(folder, 'events.jsonl', row.get('raw_events_sha256'),
                                  required=not row.get('infrastructure_failure'))
        stderr = _bound_bytes(folder, 'stderr.log', row.get('stderr_sha256'),
                             required=not row.get('infrastructure_failure'))
        answer = _bound_bytes(folder, 'answer.json',
                              row.get('answer_sha256') if (folder / 'answer.json').is_file() else None,
                              required=False)
        if answer is None:
            answer = b''
            if row.get('answer_sha256') not in (None, digest(answer)):
                raise ValueError('missing answer with nonempty claimed hash')
        if row.get('answer_sha256') is not None and digest(answer) != row['answer_sha256']:
            raise ValueError('answer hash differs from actual answer')
        events, usage, diagnostics = None, None, None
        if raw_events is not None:
            try:
                events = [json.loads(line) for line in raw_events.splitlines() if line.strip()]
            except ValueError as error:
                if row.get('instrumentation_valid') or row.get('usage') is not None:
                    raise ValueError('malformed raw events claimed as valid') from error
            if events is not None:
                try:
                    usage = trials.support.parse_usage(events)
                except ValueError as error:
                    if row.get('instrumentation_valid') or row.get('usage') is not None:
                        raise ValueError('raw usage has missing, duplicate or invalid counters') from error
                diagnostics = trials.support.diagnostics(events)
                diagnostics['stderr_policy_rejections'] = len(re.findall(r'Rejected\(', (stderr or b'').decode(errors='replace')))
                if row.get('diagnostics') is not None and diagnostics != row['diagnostics']:
                    raise ValueError('diagnostics differ from raw events or stderr')
        if usage != row.get('usage'):
            raise ValueError('usage summary differs from raw counters')
        instrumentation = bool(usage is not None and row.get('exit_code') == 0
                               and row.get('timed_out') is False and not row.get('infrastructure_failure'))
        if row.get('instrumentation_valid') is not instrumentation:
            raise ValueError('instrumentation-valid claim contradicts recorded outcome')
        if trials.tasks.manifest(folder / 'workspace') != row.get('workspace_after_sha256'):
            raise ValueError('final workspace differs from recorded manifest')
        correctness = trials.tasks.evaluate(row['task'], folder / 'workspace', answer.decode())
        correctness_comparison = _compare_correctness(row.get('correctness', {}), correctness,
                                                      row.get('infrastructure_failure', False))
        if row['arm'] == 'native':
            definitions = []
        elif (folder / 'tool-definitions.json').is_file():
            definitions = _load(folder / 'tool-definitions.json')
        elif row.get('infrastructure_failure'):
            definitions = None
        else:
            raise ValueError('tool definition evidence missing')
        if definitions is not None:
            if (row.get('tools') != [item['name'] for item in definitions]
                    or row.get('tool_schema_sha256') != digest(stable(definitions))
                    or row.get('tool_schema_json_bytes') != (0 if row['arm'] == 'native' else len(stable(definitions)))):
                raise ValueError('tool definitions differ from recorded profile')
            if row['arm'] == 'navigation' and set(row['tools']) != trials.NAVIGATION:
                raise ValueError('navigation profile does not expose registered tools')
            if row['arm'] != 'native':
                capabilities = _load(folder / 'capabilities.json')
                backend = {key: capabilities.get(key) for key in BACKEND_FIELDS}
                if row.get('backend') != backend:
                    raise ValueError('reported backend differs from captured capabilities')
                row['backend_sha256'] = digest(stable(backend))
                contract = _load(folder / 'mcp-contract.json')
                if (digest(stable(contract)) != row.get('mcp_contract_sha256')
                        or contract.get('tools') != definitions or not isinstance(contract.get('initialize'), dict)):
                    raise ValueError('actual MCP contract differs from captured profile')
                source_files = sum(Path(name).suffix in SOURCE_EXTENSIONS
                                   for name in spec['workspace_files'])
                if row.get('admission') != {'expected_source_files': source_files, 'indexed_source_files': source_files}:
                    raise ValueError('indexed source admission differs from fixture')
        bound_raw = (raw_events is not None and stderr is not None and command_present
                     and row.get('raw_events_sha256') == digest(raw_events)
                     and row.get('stderr_sha256') == digest(stderr)
                     and row.get('answer_sha256') == digest(answer))
        review = _review(reviews.get(row['id'], {}), row.get('raw_events_sha256'), row.get('stderr_sha256'))
        row.update(usage=usage, correctness_recheck=correctness, correctness_comparison=correctness_comparison,
                   diagnostics=diagnostics or {},
                   access_audit=review, valid_measurement=bool(instrumentation and bound_raw and review['passed']),
                   correctness_rechecked=True, raw_binding_complete=bool(bound_raw))
        row['actual_read_retrieval'] = _read_retrieval(row)
        rows.append(row)
    return frozen, rows


def _percent(baseline, treatment):
    return 100 * (baseline - treatment) / baseline if baseline else None


def _read_retrieval(row):
    calls = row.get('diagnostics', {}).get('tool_calls', {})
    return any(calls.get('mcp:' + name, 0) > 0 for name in READ_TOOLS)


def summarize(rows, frozen=None):
    expected_cells = {(item['task'], item['repetition']) for item in (frozen or {'schedule': _schedule()})['schedule']}
    index = {(row['task'], row['repetition'], row['arm']): row for row in rows}
    if len(index) != len(rows):
        raise ValueError('duplicate task/repetition/arm cell')
    common = []
    for task, repetition in sorted(expected_cells):
        values = [index.get((task, repetition, arm)) for arm in ARMS]
        if all(value and value['valid_measurement'] and value['correctness']['passed']
               and value.get('usage') is not None for value in values):
            common.append((task, repetition))
    arms = {}
    for arm in ARMS:
        attempts = [row for row in rows if row['arm'] == arm]
        measured = [row for row in attempts if row.get('usage') is not None]
        valid = [row for row in attempts if row['valid_measurement']]
        valid_correct = [row for row in valid if row['correctness']['passed']]
        calls = Counter()
        for row in attempts:
            calls.update(row.get('diagnostics', {}).get('tool_calls', {}))
        arms[arm] = {
            'attempts': len(attempts), 'correct_attempts': sum(row['correctness']['passed'] for row in attempts),
            'valid': len(valid), 'valid_correct': len(valid_correct), 'missing_usage': len(attempts) - len(measured),
            'retrieval_attempts': sum(_read_retrieval(row) for row in attempts),
            'valid_correct_with_retrieval': sum(_read_retrieval(row) for row in valid_correct),
            'infrastructure_failures': sum(bool(row.get('infrastructure_failure')) for row in attempts),
            'timeouts': sum(bool(row.get('timed_out')) for row in attempts),
            'access_review_statuses': dict(Counter(row.get('access_audit', {}).get('status', 'pending') for row in attempts)),
            'policy_rejections': sum(row.get('diagnostics', {}).get('stderr_policy_rejections', 0) for row in attempts),
            'all_known_usage_totals': {metric: sum(row['usage'][metric] for row in measured) for metric in METRICS},
            'valid_usage_totals': {metric: sum(row['usage'][metric] for row in valid) for metric in METRICS},
            'matched_usage_totals': {metric: sum(index[(task, rep, arm)]['usage'][metric] for task, rep in common) for metric in METRICS},
            'matched_cells': len(common), 'tool_calls': dict(calls),
            'elapsed_seconds': sum(row.get('elapsed_seconds', 0) for row in attempts),
            'setup_seconds': sum(row.get('setup_seconds', 0) for row in attempts),
            'median_elapsed_seconds': statistics.median(row['elapsed_seconds'] for row in attempts if 'elapsed_seconds' in row)
                if any('elapsed_seconds' in row for row in attempts) else None,
        }
    comparisons, pairs = [], []
    for baseline, treatment in COMPARISONS:
        left, right = arms[baseline]['matched_usage_totals'], arms[treatment]['matched_usage_totals']
        comparisons.append({'baseline': baseline, 'treatment': treatment, 'matched_cells': len(common),
                            'baseline_totals': left, 'treatment_totals': right,
                            'percent_decrease': {metric: _percent(left[metric], right[metric]) for metric in METRICS}})
        for task, rep in common:
            left, right = index[(task, rep, baseline)]['usage'], index[(task, rep, treatment)]['usage']
            pairs.append({'task': task, 'repetition': rep, 'baseline': baseline, 'treatment': treatment,
                          'baseline_usage': left, 'treatment_usage': right,
                          'percent_decrease': {metric: _percent(left[metric], right[metric]) for metric in METRICS}})
    threshold = {}
    for arm in ARMS[1:]:
        summary = arms[arm]
        comparison = next(value for value in comparisons if value['baseline'] == 'native' and value['treatment'] == arm)
        input_decrease = comparison['percent_decrease']['input_tokens']
        output_decrease = comparison['percent_decrease']['output_tokens']
        checks = {
            'complete_common_valid_correct_cells': bool(common) and len(common) == len(expected_cells),
            'all_treatment_attempts_correct': summary['attempts'] == len(expected_cells) == summary['correct_attempts'],
            'input_decrease_at_least_20_percent': input_decrease is not None and input_decrease >= 20,
            'no_output_increase': output_decrease is not None and output_decrease >= 0,
            'retrieval_in_at_least_half_attempts': summary['attempts'] > 0 and summary['retrieval_attempts'] * 2 >= summary['attempts'],
        }
        threshold[arm] = {'passed': all(checks.values()), 'checks': checks}
    return {
        'attempts': len(rows), 'planned_attempts': len(expected_cells) * len(ARMS),
        'percentage_convention': '100 * (baseline - treatment) / baseline; negative means an increase',
        'common_cells': [{'task': task, 'repetition': repetition} for task, repetition in common],
        'excluded_cells': [{'task': task, 'repetition': repetition} for task, repetition in sorted(expected_cells - set(common))],
        'arms': arms, 'comparisons': comparisons, 'pairs': pairs, 'continuation_threshold': threshold,
    }


def _sanitized(row):
    fields = ('id', 'task', 'arm', 'repetition', 'usage', 'correctness', 'instrumentation_valid',
              'valid_measurement', 'infrastructure_failure', 'timed_out', 'exit_code', 'setup_seconds',
              'elapsed_seconds', 'fixture_sha256', 'workspace_before_sha256', 'workspace_after_sha256',
              'prompt_sha256', 'schema_sha256', 'frozen_sha256', 'evaluator_sha256', 'raw_events_sha256',
              'stderr_sha256', 'answer_sha256', 'tool_schema_sha256', 'tool_schema_json_bytes', 'tools',
              'mcp_contract_sha256', 'admission', 'backend_sha256', 'diagnostics', 'access_audit', 'correctness_rechecked',
              'correctness_recheck', 'correctness_comparison', 'actual_read_retrieval', 'raw_binding_complete')
    result = {field: row[field] for field in fields if field in row}
    if 'backend' in row:
        backend = json.loads(json.dumps(row['backend']))
        adapters = backend.get('language_adapters') or {}
        for adapter in adapters.values():
            if isinstance(adapter, dict) and 'diagnostics' in adapter:
                diagnostics = adapter.pop('diagnostics')
                adapter['diagnostic_count'] = len(diagnostics)
                adapter['diagnostic_sha256'] = [digest(stable(value)) for value in diagnostics]
        result['backend'] = backend
    return result


def _format_percent(value):
    return 'unavailable' if value is None else f'{value:+.1f}%'


def render(frozen, rows, summary, output):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    safe_rows = [_sanitized(row) for row in rows]
    metadata = {name: frozen[name] for name in ('schema_version', 'model', 'model_identity', 'codex_version',
                                               'repository_head', 'schedule_seed', 'schedule', 'artifact_hashes')}
    metadata['integration_guidance_sha256'] = digest(frozen['integration_guidance'].encode())
    metadata['integration_guidance_utf8_bytes'] = len(frozen['integration_guidance'].encode())
    metadata['task_bindings'] = {task: {'manifest_sha256': spec['manifest_sha256'],
                                       'source_bytes': spec['source_bytes'], 'source_lines': spec['source_lines'],
                                       'source_files': sum(Path(name).suffix in SOURCE_EXTENSIONS for name in spec['workspace_files']),
                                       'actual_prompt_sha256_by_arm': {
                                           arm: digest(_actual_prompt(frozen, task, arm).encode()) for arm in ARMS}}
                                 for task, spec in frozen['tasks'].items()}
    trials.write_json(output / 'bindings.json', metadata)
    trials.write_json(output / 'runs.json', safe_rows)
    trials.write_json(output / 'summary.json', summary)
    with (output / 'runs.csv').open('w') as stream:
        names = ['id', 'task', 'repetition', 'arm', 'correct', 'valid', *METRICS,
                 'elapsed_seconds', 'setup_seconds', 'read_retrieval_attempted', 'policy_rejections', 'access_status']
        writer = csv.DictWriter(stream, fieldnames=names, lineterminator='\n')
        writer.writeheader()
        for row in rows:
            writer.writerow({**{key: row.get(key) for key in names if key in row},
                             **{metric: (row.get('usage') or {}).get(metric) for metric in METRICS},
                             'correct': row['correctness']['passed'], 'valid': row['valid_measurement'],
                             'read_retrieval_attempted': _read_retrieval(row),
                             'policy_rejections': row.get('diagnostics', {}).get('stderr_policy_rejections'),
                             'access_status': row.get('access_audit', {}).get('status')})
    lines = ['# Fresh retrieval-repair confirmation', '',
             f"{len(rows)} / {len(frozen['schedule'])} scored attempts retained; "
             f"{sum(row['valid_measurement'] for row in rows)} valid after raw-evidence and access checks. "
             'Every correctness result was recomputed by the external evaluator.', '',
             f"Configured model: `{frozen['model']}`; client: `{frozen['codex_version']}`. "
             'Input includes cached input; output includes reasoning. Counters are not independently reconciled billing.', '',
             '| Configuration | Correct / valid / attempted | Read retrieval attempted / runs | Stderr approval rejections | Known input | Known output |',
             '| --- | ---: | ---: | ---: | ---: | ---: |']
    for arm, result in summary['arms'].items():
        totals = result['all_known_usage_totals']
        lines += [f"| {arm} | {result['correct_attempts']} / {result['valid']} / {result['attempts']} | "
                  f"{result['retrieval_attempts']} / {result['attempts']} | {result['policy_rejections']} | "
                  f"{totals['input_tokens']:,} | {totals['output_tokens']:,} |"]
    lines += ['', 'Known totals include invalid and incorrect attempts with available counters. '
              'They are not the denominator for savings. Missing counters remain unknown. '
              'Adoption counts attempts at graph/source retrieval, including failed or unhelpful responses; '
              'it does not measure retrieval usefulness. Capability, edit and coordination calls do not count.', '',
              '## Matched percentage decreases', '',
              f"All comparisons below use the same {len(summary['common_cells'])} task/repetition cells, "
              'requiring all four configurations to be valid and correct. Positive values mean fewer tokens; '
              '**negative values mean more tokens**. Formula: `100 × (baseline − treatment) / baseline`.', '',
              '| Treatment versus baseline | Cells | Input decrease | Output decrease | Uncached-input decrease |',
              '| --- | ---: | ---: | ---: | ---: |']
    for comparison in summary['comparisons']:
        values = comparison['percent_decrease']
        lines += [f"| {comparison['treatment']} vs {comparison['baseline']} | {comparison['matched_cells']} | "
                  f"{_format_percent(values['input_tokens'])} | {_format_percent(values['output_tokens'])} | "
                  f"{_format_percent(values['uncached_input_tokens'])} |"]
    lines += ['', '## Every attempt', '',
              '| Task | Rep | Arm | Correct | Valid | Input | Cached | Output | Retrieval | Access | Stderr approval rejections |',
              '| --- | ---: | --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: |']
    for row in rows:
        usage, diagnostic = row.get('usage') or {}, row.get('diagnostics', {})
        lines += [f"| {row['task']} | {row['repetition']} | {row['arm']} | {row['correctness']['passed']} | "
                  f"{row['valid_measurement']} | {usage.get('input_tokens', 'unknown')} | "
                  f"{usage.get('cached_input_tokens', 'unknown')} | {usage.get('output_tokens', 'unknown')} | "
                  f"{_read_retrieval(row)} | {row.get('access_audit', {}).get('status', 'pending')} | "
                  f"{diagnostic.get('stderr_policy_rejections', 'unknown')} |"]
    lines += ['', '## Every matched pair', '',
              '| Task | Rep | Treatment versus baseline | Input decrease | Output decrease |',
              '| --- | ---: | --- | ---: | ---: |']
    for pair in summary['pairs']:
        lines += [f"| {pair['task']} | {pair['repetition']} | {pair['treatment']} vs {pair['baseline']} | "
                  f"{_format_percent(pair['percent_decrease']['input_tokens'])} | "
                  f"{_format_percent(pair['percent_decrease']['output_tokens'])} |"]
    lines += ['', '## Registered continuation threshold', '',
              'At least 20% lower input than native, no output increase, all tasks correct, and retrieval '
              'in at least half the treatment attempts. Incomplete common measurement coverage cannot pass.', '']
    for arm, decision in summary['continuation_threshold'].items():
        failed = ', '.join(name for name, passed in decision['checks'].items() if not passed)
        lines += [f"- {arm}: {'passed' if decision['passed'] else 'not passed'}" + (f' ({failed}).' if failed else '.')]
    lines += ['', '## Limits and evidence', '',
              'Three generated tasks, two repetitions and two source corpora constitute a development confirmation, '
              'not a production benchmark or statistical generalization. Provider cache state is uncontrolled. '
              'Repaired and navigation receive explicit integration guidance, including its input overhead; '
              'native and legacy do not. Repaired versus legacy changes resolution and integration together. '
              'Navigation changes tool availability and response format together. Low tool adoption limits attribution. '
              'Stderr approval rejections count only the observed Rejected( marker; sandbox or runtime failures '
              'inside command output remain separate tool errors. Zero in that column does not imply no tool failures.', '',
              'Private raw transcripts, stderr, prompts, answers and final workspaces are bound by digests in runs.json. '
              'The collector verifies per-run records, rederives usage and diagnostics, checks the frozen artifact '
              'and fixture identities, and reruns the external correctness evaluator. Access review is a separate '
              'human/agent judgment and is not proved by a hash. Calibration is not included in these scored totals. '
              'Backend and adapter metadata in runs.json matches captured capabilities; external system runtime '
              'binaries are not fully pinned. Admission checks indexed file counts, not a per-file graph identity proof. '
              'Failed-compilation diagnostic hashes may change with private evaluator temporary paths; '
              'both hashes remain recorded and all semantic correctness fields must still agree. '
              'bindings.json omits local launcher/workspace paths; raw prompts, answers and access-review prose '
              'are not copied into this report.', '']
    (output / 'REPORT.md').write_text('\n'.join(lines))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--artifacts', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    frozen, rows = collect(args.artifacts)
    render(frozen, rows, summarize(rows, frozen), args.output)
