from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap

TASK_IDS = ('java_chain', 'polyglot_lookup', 'java_repair')
FIELDS = {
    'java_chain': ('decision', 'sink'),
    'polyglot_lookup': ('typescript_symbol', 'python_symbol'),
    'java_repair': ('changed_file', 'status'),
}


def contents(root):
    return {str(path.relative_to(root)): path.read_bytes()
            for path in sorted(root.rglob('*')) if path.is_file()}


def manifest(root):
    return hashlib.sha256(json.dumps(
        {name: hashlib.sha256(data).hexdigest() for name, data in contents(root).items()},
        sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def write(root, name, source):
    path = root / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(source).strip() + '\n')


def java_corpus(root):
    sources = {
        'Order.java': '''
            public record Order(String tier, int seats, boolean cancelled) {
                public boolean isEmpty() { return seats == 0; }
                public Order withSeats(int count) { return new Order(tier, count, cancelled); }
            }
        ''',
        'Submission.java': '''
            public class Submission {
                public static String submit(Order order) {
                    if (order == null) throw new IllegalArgumentException("order");
                    return OrderRouter.route(order);
                }
                public static String preview(Order order) {
                    return PreviewRouter.route(order);
                }
            }
        ''',
        'OrderRouter.java': '''
            public class OrderRouter {
                public static String route(Order order) {
                    if (order.cancelled()) return CancellationAudit.record(order);
                    if (order.isEmpty()) return RejectionAudit.record(order);
                    return EligibilityGate.authorize(order);
                }
                public static boolean accepts(Order order) { return order.seats() >= 0; }
            }
        ''',
        'EligibilityGate.java': '''
            public class EligibilityGate {
                public static String authorize(Order order) {
                    if (order.seats() < 0) return RejectionAudit.record(order);
                    if (!CapacityBook.hasSeats(order.seats())) return WaitlistAudit.record(order);
                    return ApprovalService.authorize(order);
                }
                public static boolean permitsRetry(Order order) { return !order.cancelled(); }
            }
        ''',
        'ApprovalService.java': '''
            public class ApprovalService {
                public static String authorize(Order order) {
                    if (TierPolicy.authorize(order)) return AuditLedger.appendApproved(order);
                    if (PublicPolicy.authorize(order)) return AuditLedger.appendPending(order);
                    return RejectionAudit.record(order);
                }
                public static String preview(Order order) { return PreviewRouter.route(order); }
            }
        ''',
        'TierPolicy.java': '''
            public class TierPolicy {
                public static boolean authorize(Order order) {
                    if (!order.tier().equals("VIP")) return false;
                    return order.seats() > 0 && order.seats() <= 4;
                }
                public static int maximumSeats() { return 4; }
            }
        ''',
        'PublicPolicy.java': '''
            public class PublicPolicy {
                public static boolean authorize(Order order) {
                    return order.tier().equals("PUBLIC") && order.seats() <= 2;
                }
                public static boolean requiresReview(Order order) { return order.seats() > 2; }
            }
        ''',
        'CapacityBook.java': '''
            public class CapacityBook {
                public static boolean hasSeats(int requested) { return requested <= 8; }
                public static int remaining(int requested) { return Math.max(0, 8 - requested); }
                public static boolean full(int requested) { return remaining(requested) == 0; }
            }
        ''',
        'AuditLedger.java': '''
            public class AuditLedger {
                public static String appendApproved(Order order) { return "ALLOW"; }
                public static String appendPending(Order order) { return "PENDING"; }
                public static String record(Order order) { return "RECORDED"; }
                public static String category(Order order) { return order.tier(); }
            }
        ''',
        'PreviewRouter.java': '''
            public class PreviewRouter {
                public static String route(Order order) {
                    if (order.cancelled()) return "PREVIEW_CANCEL";
                    return TierPolicy.authorize(order) ? "PREVIEW_ALLOW" : "PREVIEW_REVIEW";
                }
                public static boolean persisted() { return false; }
            }
        ''',
        'CouponPolicy.java': '''
            public class CouponPolicy {
                public int discount(int subtotal, int minimum, int percent) {
                    if (subtotal < 0 || minimum < 0 || percent < 0 || percent > 100) {
                        throw new IllegalArgumentException("coupon inputs");
                    }
                    if (subtotal <= minimum) return 0;
                    return (int) ((long) subtotal * percent / 100);
                }
                public int payable(int subtotal, int minimum, int percent) {
                    return subtotal - discount(subtotal, minimum, percent);
                }
                public String describe(int percent) { return percent + "%"; }
            }
        ''',
        'Invoice.java': '''
            public class Invoice {
                private final CouponPolicy policy = new CouponPolicy();
                public int total(int units, int price, int minimum, int percent) {
                    if (units < 0 || price < 0) throw new IllegalArgumentException();
                    int subtotal = Math.multiplyExact(units, price);
                    return policy.payable(subtotal, minimum, percent);
                }
                public int undiscounted(int units, int price) { return Math.multiplyExact(units, price); }
            }
        ''',
        'RefundPolicy.java': '''
            public class RefundPolicy {
                public int refund(int paid, int used) {
                    if (paid < 0 || used < 0) throw new IllegalArgumentException();
                    return Math.max(0, paid - used);
                }
                public boolean eligible(Order order) { return order.cancelled(); }
                public String authorize(Order order) { return eligible(order) ? "REFUND" : "HOLD"; }
            }
        ''',
        'ReceiptFormatter.java': '''
            public class ReceiptFormatter {
                public String format(Order order, int paid) {
                    return order.tier() + ":" + order.seats() + ":" + paid;
                }
                public String record(Order order) { return format(order, 0); }
                public String heading() { return "Receipt"; }
            }
        ''',
    }
    for name, outcome in (('CancellationAudit', 'CANCELLED'), ('RejectionAudit', 'DENY'),
                          ('WaitlistAudit', 'WAITLIST'), ('DeliveryAudit', 'DELIVERED')):
        sources[name + '.java'] = f'''
            public class {name} {{
                public static String record(Order order) {{ return "{outcome}"; }}
                public static String category() {{ return "order"; }}
                public static int quantity(Order order) {{ return order.seats(); }}
                public static boolean relevant(Order order) {{ return order != null; }}
            }}
        '''
    for name, source in sources.items():
        write(root, name, source)


def polyglot_corpus(root):
    policies = {
        'payments': (2, 'cancel', 'settled', 'retry'),
        'shipments': (3, 'cancel', 'shipped', 'retry'),
        'refunds': (2, 'held', 'refunded', 'wait'),
        'notifications': (1, 'discard', 'sent', 'retry'),
        'invoices': (2, 'cancel', 'issued', 'wait'),
        'reservations': (4, 'cancel', 'settled', 'retry'),
    }
    for domain, (threshold, cancelled, finished, waiting) in policies.items():
        ts = f'''
            export interface Attempt {{ count: number; cancelled: boolean; }}
            export function normalize(attempt: Attempt): Attempt {{
                return {{ count: Math.max(0, attempt.count), cancelled: attempt.cancelled }};
            }}
            export function resolve(attempt: Attempt): string {{
                const current = normalize(attempt);
                if (current.cancelled) return '{cancelled}';
                return current.count >= {threshold} ? '{finished}' : '{waiting}';
            }}
            export function terminal(attempt: Attempt): boolean {{
                return resolve(attempt) === '{finished}';
            }}
            export function preview(attempt: Attempt): string {{
                return attempt.cancelled ? 'held' : 'uncommitted';
            }}
        '''
        py = f'''
            def normalize(count: int, cancelled: bool) -> tuple[int, bool]:
                return max(0, count), cancelled

            def resolve(count: int, cancelled: bool) -> str:
                count, cancelled = normalize(count, cancelled)
                if cancelled:
                    return '{cancelled}'
                return '{finished}' if count >= {threshold} else '{waiting}'

            def terminal(count: int, cancelled: bool) -> bool:
                return resolve(count, cancelled) == '{finished}'

            def preview(count: int, cancelled: bool) -> str:
                return 'held' if cancelled else 'uncommitted'
        '''
        write(root, 'web/' + domain + '.ts', ts)
        write(root, 'ops/' + domain + '.py', py)
    write(root, 'web/checkout.ts', '''
        import { resolve, Attempt } from './payments';
        export function submit(attempt: Attempt): string { return resolve(attempt); }
        export function accepted(attempt: Attempt): boolean { return submit(attempt) === 'settled'; }
    ''')
    write(root, 'ops/batch.py', '''
        from payments import resolve
        def reconcile(counts: list[int]) -> list[str]:
            return [resolve(count, False) for count in counts]
    ''')


def create_task(task_id, directory):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    root = Path(directory)
    if root.exists() and any(root.iterdir()):
        raise ValueError('task directory must be empty')
    root.mkdir(parents=True, exist_ok=True)
    if task_id == 'polyglot_lookup':
        polyglot_corpus(root)
        prompt = ('Find the TypeScript and Python functions that return "settled" for a '
                  'non-cancelled payment starting at attempt two, "retry" before that, and '
                  '"cancel" for cancelled payments regardless of attempt count. Several '
                  'modules have the same function names. Return each as relative/path:symbol. '
                  'Do not modify files. Reply with exactly one JSON object with string '
                  'fields typescript_symbol and python_symbol.')
    else:
        java_corpus(root)
        if task_id == 'java_chain':
            prompt = ('For Submission.submit(new Order("VIP", 3, false)), follow the Java '
                      'submission path to its final audit sink. Give the returned decision '
                      'and the Class.method that creates it. Do not modify files. Reply '
                      'with exactly one JSON object with string fields decision and sink.')
        else:
            prompt = ('Fix the boundary defect in CouponPolicy.discount: a subtotal exactly '
                      'at the minimum qualifying amount must receive the configured '
                      'discount. Preserve other behavior, including invalid inputs and '
                      'large subtotals. Modify only CouponPolicy.java. You may run javac '
                      'and local probes, but remove generated artifacts before finishing. '
                      'Reply with exactly one JSON object with string fields changed_file '
                      'and status; status should be "fixed" only when the change is complete.')
    write(root, 'TASK.md', prompt)
    return {
        'task_id': task_id, 'prompt': prompt,
        'response_schema': {'type': 'object', 'required': list(FIELDS[task_id]),
                            'additionalProperties': False,
                            'properties': {key: {'type': 'string'} for key in FIELDS[task_id]}},
        'writable': task_id == 'java_repair', 'workspace_files': list(contents(root)),
        'manifest_sha256': manifest(root),
        'source_bytes': sum(len(data) for name, data in contents(root).items() if name != 'TASK.md'),
        'source_lines': sum(data.count(b'\n') for name, data in contents(root).items() if name != 'TASK.md'),
    }


def evaluate(task_id, directory, final_text):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    try:
        answer = json.loads(final_text)
    except ValueError:
        return {'passed': False, 'details': 'invalid JSON'}
    if (not isinstance(answer, dict) or set(answer) != set(FIELDS[task_id])
            or any(type(value) is not str for value in answer.values())):
        return {'passed': False, 'details': 'wrong response shape'}
    root = Path(directory)
    with tempfile.TemporaryDirectory(prefix='graphharness-external-eval-') as directory:
        base = Path(directory) / 'original'
        create_task(task_id, base)
        expected, observed = contents(base), contents(root)
        if set(expected) != set(observed) or any(path.is_symlink() for path in root.rglob('*')):
            return {'passed': False, 'details': 'unexpected, missing or symlinked workspace files'}
        changed = [name for name in expected if expected[name] != observed[name]]
        allowed = ['CouponPolicy.java'] if task_id == 'java_repair' else []
        if changed != allowed:
            return {'passed': False, 'details': 'wrong change scope', 'changed_files': changed}
        if task_id == 'java_chain':
            passed = answer == {'decision': 'ALLOW', 'sink': 'AuditLedger.appendApproved'}
            return {'passed': passed, 'details': 'exact branch/sink check', 'changed_files': changed}
        if task_id == 'polyglot_lookup':
            passed = answer == {'typescript_symbol': 'web/payments.ts:resolve',
                                'python_symbol': 'ops/payments.py:resolve'}
            return {'passed': passed, 'details': 'exact file/symbol check', 'changed_files': changed}
        if answer != {'changed_file': 'CouponPolicy.java', 'status': 'fixed'}:
            return {'passed': False, 'details': 'repair not correctly reported'}
        copy = Path(directory) / 'tested'
        shutil.copytree(root, copy)
        write(copy, 'ExternalProbe.java', '''
            public class ExternalProbe {
                public static void main(String[] args) {
                    CouponPolicy policy = new CouponPolicy();
                    int[][] cases = {{100,100,25,25},{99,100,25,0},{101,100,25,25},
                        {0,0,100,0},{100,100,0,0},{2000000000,1,100,2000000000},
                        {2000000000,1,25,500000000},{7,7,50,3}};
                    for (int[] row : cases) {
                        if (policy.discount(row[0],row[1],row[2]) != row[3])
                            throw new AssertionError("discount behavior");
                    }
                    int[][] invalid = {{-1,1,1},{1,-1,1},{1,1,-1},{1,1,101}};
                    for (int[] row : invalid) {
                        try {
                            policy.discount(row[0],row[1],row[2]);
                            throw new AssertionError("missing invalid-input rejection");
                        } catch (IllegalArgumentException expected) {}
                    }
                    if (policy.payable(100,100,25) != 75) throw new AssertionError("payable");
                    if (!policy.describe(25).equals("25%")) throw new AssertionError("describe");
                }
            }
        ''')
        build = copy / 'classes'
        build.mkdir()
        compile_run = subprocess.run(['javac', '-d', str(build), *map(str, sorted(copy.glob('*.java')))],
                                     capture_output=True, timeout=30)
        run = None if compile_run.returncode else subprocess.run(
            ['java', '-cp', str(build), 'ExternalProbe'], capture_output=True, timeout=10)
        passed = run is not None and run.returncode == 0
        evidence = compile_run.stdout + compile_run.stderr
        if run is not None:
            evidence += run.stdout + run.stderr
        return {'passed': passed, 'details': 'external compile and 14 behavior assertions',
                'changed_files': changed, 'compile_exit': compile_run.returncode,
                'test_exit': None if run is None else run.returncode,
                'test_output_sha256': hashlib.sha256(evidence).hexdigest(),
                'diff_sha256': hashlib.sha256(observed['CouponPolicy.java']).hexdigest()}
