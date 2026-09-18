from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import textwrap

TASK_IDS = ('java_dispatch', 'polyglot_retry', 'java_backoff')
FIELDS = {
    'java_dispatch': ('outcome', 'destination', 'sink'),
    'polyglot_retry': ('typescript_symbol', 'python_symbol', 'typescript_result', 'python_result'),
    'java_backoff': ('changed_file', 'status'),
}
JAVA_PREFIX = 'src/main/java/relay/'
REPAIR_FILE = JAVA_PREFIX + 'BackoffPolicy.java'


def contents(root):
    root = Path(root)
    return {path.relative_to(root).as_posix(): path.read_bytes()
            for path in sorted(root.rglob('*'))
            if path.is_file() and path.relative_to(root).parts[0] != '.scratch'}


def manifest(root):
    hashes = {name: hashlib.sha256(data).hexdigest() for name, data in contents(root).items()}
    return hashlib.sha256(json.dumps(hashes, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def write(root, name, source):
    path = Path(root) / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(source).strip() + '\n', encoding='utf-8')


def java_corpus(root):
    sources = {
        'DispatchRequest': '''
            public record DispatchRequest(String zone, int units, boolean fragile, boolean cancelled) {
                public DispatchRequest withZone(String replacement) {
                    return new DispatchRequest(replacement, units, fragile, cancelled);
                }
            }
        ''',
        'DispatchDecision': '''
            public record DispatchDecision(String outcome, String destination) {
                public boolean accepted() { return outcome.equals("QUEUED"); }
                public String summary() { return destination + ":" + outcome; }
            }
        ''',
        'DispatchService': '''
            public class DispatchService {
                public static DispatchDecision dispatch(DispatchRequest request) {
                    RequestValidator.validate(request);
                    if (request.cancelled()) return CancellationLedger.record(request);
                    DispatchRequest normalized = ZoneNormalizer.normalize(request);
                    return DispatchRouter.route(normalized);
                }
                public static DispatchDecision preview(DispatchRequest request) {
                    return PreviewService.dispatch(request);
                }
            }
        ''',
        'RequestValidator': '''
            public class RequestValidator {
                public static void validate(DispatchRequest request) {
                    if (request == null || request.zone() == null || request.units() <= 0)
                        throw new IllegalArgumentException("invalid dispatch request");
                }
                public static boolean validUnits(int units) { return units > 0 && units <= 1000000; }
            }
        ''',
        'ZoneNormalizer': '''
            public class ZoneNormalizer {
                public static DispatchRequest normalize(DispatchRequest request) {
                    String zone = request.zone().trim().toLowerCase(java.util.Locale.ROOT);
                    if (zone.equals("zone-b")) zone = "coast";
                    if (zone.equals("zone-a")) zone = "inland";
                    return request.withZone(zone);
                }
            }
        ''',
        'DispatchRouter': '''
            public class DispatchRouter {
                public static DispatchDecision route(DispatchRequest request) {
                    if (request.zone().equals("coast")) return CoastDispatcher.dispatch(request);
                    if (request.zone().equals("inland")) return InlandDispatcher.dispatch(request);
                    return UnknownZoneLedger.record(request);
                }
                public static boolean known(String zone) { return zone.equals("coast") || zone.equals("inland"); }
            }
        ''',
        'CoastDispatcher': '''
            public class CoastDispatcher {
                public static DispatchDecision dispatch(DispatchRequest request) {
                    int parcels = ParcelPlanner.parcels(request);
                    if (parcels > CoastCapacity.available()) return OverflowHandler.defer(request, parcels);
                    return QueueLedger.record(request, "coast-live");
                }
                public static String destination() { return "coast-live"; }
            }
        ''',
        'InlandDispatcher': '''
            public class InlandDispatcher {
                public static DispatchDecision dispatch(DispatchRequest request) {
                    if (ParcelPlanner.parcels(request) > 6) return OverflowHandler.defer(request, 6);
                    return QueueLedger.record(request, "inland-live");
                }
                public static String destination() { return "inland-live"; }
            }
        ''',
        'ParcelPlanner': '''
            public class ParcelPlanner {
                public static int parcels(DispatchRequest request) {
                    int limit = PackingPolicy.unitsPerParcel(request.fragile());
                    return (int) (((long) request.units() + limit - 1) / limit);
                }
                public static int lastParcelUnits(DispatchRequest request) {
                    return (request.units() - 1) % PackingPolicy.unitsPerParcel(request.fragile()) + 1;
                }
            }
        ''',
        'PackingPolicy': '''
            public class PackingPolicy {
                public static int unitsPerParcel(boolean fragile) { return fragile ? 5 : 8; }
                public static boolean needsCushioning(boolean fragile, int units) { return fragile && units > 0; }
                public static String material(boolean fragile) { return fragile ? "foam" : "paper"; }
            }
        ''',
        'CoastCapacity': '''
            public class CoastCapacity {
                public static int available() { return SlotInventory.coastFree() - ReservePolicy.coastReserve(); }
                public static boolean exhausted() { return available() <= 0; }
            }
        ''',
        'SlotInventory': '''
            public class SlotInventory {
                public static int coastFree() { return 3; }
                public static int inlandFree() { return 9; }
                public static int capacity(String zone) { return zone.equals("coast") ? coastFree() : inlandFree(); }
            }
        ''',
        'ReservePolicy': '''
            public class ReservePolicy {
                public static int coastReserve() { return 1; }
                public static int emergencyReserve(int capacity) { return Math.max(1, capacity / 4); }
                public static boolean canRelease(boolean emergency) { return emergency; }
            }
        ''',
        'OverflowHandler': '''
            public class OverflowHandler {
                public static DispatchDecision defer(DispatchRequest request, int parcels) {
                    if (request.fragile() && parcels > 2) return ReviewLedger.recordDeferred(request);
                    return RetryLedger.recordDeferred(request);
                }
                public static boolean reviewRequired(DispatchRequest request) { return request.fragile(); }
            }
        ''',
        'ReviewLedger': '''
            public class ReviewLedger {
                public static DispatchDecision recordDeferred(DispatchRequest request) {
                    return new DispatchDecision("REVIEW", request.zone() + "-manual");
                }
                public static DispatchDecision recordReleased(DispatchRequest request) {
                    return new DispatchDecision("RELEASED", request.zone() + "-live");
                }
            }
        ''',
        'RetryLedger': '''
            public class RetryLedger {
                public static DispatchDecision recordDeferred(DispatchRequest request) {
                    return new DispatchDecision("RETRY", request.zone() + "-retry");
                }
                public static long expiresAt(long receivedAt) { return receivedAt + 3600000L; }
            }
        ''',
        'QueueLedger': '''
            public class QueueLedger {
                public static DispatchDecision record(DispatchRequest request, String destination) {
                    return new DispatchDecision("QUEUED", destination);
                }
                public static String key(DispatchRequest request) { return request.zone() + "/" + request.units(); }
            }
        ''',
        'CancellationLedger': '''
            public class CancellationLedger {
                public static DispatchDecision record(DispatchRequest request) {
                    return new DispatchDecision("CANCELLED", "archive");
                }
                public static boolean shouldRefund(DispatchRequest request) { return request.units() > 0; }
            }
        ''',
        'UnknownZoneLedger': '''
            public class UnknownZoneLedger {
                public static DispatchDecision record(DispatchRequest request) {
                    return new DispatchDecision("REJECTED", "unknown-zone");
                }
                public static String diagnostic(DispatchRequest request) { return "Unsupported zone: " + request.zone(); }
            }
        ''',
        'PreviewService': '''
            public class PreviewService {
                public static DispatchDecision dispatch(DispatchRequest request) {
                    RequestValidator.validate(request);
                    return new DispatchDecision("PREVIEW", ZoneNormalizer.normalize(request).zone() + "-draft");
                }
            }
        ''',
        'BackoffPolicy': '''
            public class BackoffPolicy {
                public static int delayMillis(int attempt, int baseMillis, int capMillis) {
                    if (attempt < 0 || attempt > 62 || baseMillis < 0 || capMillis < 0)
                        throw new IllegalArgumentException("invalid backoff inputs");
                    return Math.min(capMillis, baseMillis * (1 << attempt));
                }
                public static long deadline(long nowMillis, int attempt, int baseMillis, int capMillis) {
                    return Math.addExact(nowMillis, delayMillis(attempt, baseMillis, capMillis));
                }
                public static boolean immediate(int attempt, int baseMillis, int capMillis) {
                    return delayMillis(attempt, baseMillis, capMillis) == 0;
                }
            }
        ''',
        'RetryScheduler': '''
            public class RetryScheduler {
                public long schedule(long now, int attempt, boolean overloaded) {
                    return BackoffPolicy.deadline(now, attempt, overloaded ? 500 : 100, 30000);
                }
                public boolean due(long now, long scheduled) { return now >= scheduled; }
            }
        ''',
        'DispatchBatch': '''
            public class DispatchBatch {
                public java.util.List<DispatchDecision> submit(java.util.List<DispatchRequest> requests) {
                    return requests.stream().map(DispatchService::dispatch).toList();
                }
                public long accepted(java.util.List<DispatchDecision> decisions) {
                    return decisions.stream().filter(DispatchDecision::accepted).count();
                }
            }
        ''',
        'LabelFormatter': '''
            public class LabelFormatter {
                public String label(String reference, DispatchRequest request) {
                    return reference.toUpperCase(java.util.Locale.ROOT) + " / " + request.zone();
                }
                public String handling(DispatchRequest request) { return request.fragile() ? "HANDLE WITH CARE" : "STANDARD"; }
            }
        ''',
        'WeightEstimator': '''
            public class WeightEstimator {
                public long grams(int units, int each, boolean fragile) {
                    if (units < 0 || each < 0) throw new IllegalArgumentException("weight");
                    return (long) units * each + (fragile ? 120 : 40);
                }
                public long kilogramsRoundedUp(long grams) { return grams / 1000 + (grams % 1000 == 0 ? 0 : 1); }
            }
        ''',
        'ShippingFee': '''
            public class ShippingFee {
                public long cents(long grams, boolean express) {
                    if (grams < 0) throw new IllegalArgumentException("negative weight");
                    return 250 + (grams / 500) * 35 + (express ? 400 : 0);
                }
                public boolean prepaid(long credit, long fee) { return credit >= fee; }
            }
        ''',
        'AddressNormalizer': '''
            public class AddressNormalizer {
                public String postalCode(String input) { return input.replace(" ", "").toUpperCase(java.util.Locale.ROOT); }
                public String line(String input) { return input.trim().replaceAll("\\\\s+", " "); }
            }
        ''',
        'AddressValidator': '''
            public class AddressValidator {
                public boolean valid(String street, String postalCode) {
                    return street != null && !street.isBlank() && postalCode != null && postalCode.length() >= 4;
                }
                public boolean requiresUnit(String street) { return street != null && street.contains("Apartment"); }
            }
        ''',
        'DeliveryWindow': '''
            public record DeliveryWindow(int startHour, int endHour) {
                public boolean contains(int hour) { return hour >= startHour && hour < endHour; }
                public int duration() { return endHour - startHour; }
                public boolean overlaps(DeliveryWindow other) { return startHour < other.endHour && other.startHour < endHour; }
            }
        ''',
        'HolidayCalendar': '''
            public class HolidayCalendar {
                private final java.util.Set<java.time.LocalDate> closed;
                public HolidayCalendar(java.util.Set<java.time.LocalDate> closed) { this.closed = java.util.Set.copyOf(closed); }
                public boolean workingDay(java.time.LocalDate day) {
                    return day.getDayOfWeek() != java.time.DayOfWeek.SUNDAY && !closed.contains(day);
                }
            }
        ''',
        'TrackingCode': '''
            public class TrackingCode {
                public String create(String depot, long sequence) { return depot + "-" + Long.toString(sequence, 36); }
                public String depot(String code) {
                    int dash = code.indexOf('-');
                    if (dash <= 0) throw new IllegalArgumentException("tracking code");
                    return code.substring(0, dash);
                }
            }
        ''',
        'Receipt': '''
            public record Receipt(String tracking, long paidCents, String destination) {
                public String render() { return tracking + " | " + destination + " | " + paidCents; }
                public boolean paid() { return paidCents > 0; }
            }
        ''',
        'RefundCalculator': '''
            public class RefundCalculator {
                public long refundable(long paid, long processingFee, boolean dispatched) {
                    if (paid < 0 || processingFee < 0) throw new IllegalArgumentException("money");
                    return dispatched ? 0 : Math.max(0, paid - processingFee);
                }
            }
        ''',
        'ReturnWindow': '''
            public class ReturnWindow {
                public boolean accepts(java.time.LocalDate delivery, java.time.LocalDate requested) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(delivery, requested);
                    return days >= 0 && days <= 30;
                }
            }
        ''',
        'InventoryCounter': '''
            public class InventoryCounter {
                private int units;
                public InventoryCounter(int units) { this.units = units; }
                public synchronized boolean reserve(int requested) {
                    if (requested < 0 || requested > units) return false;
                    units -= requested;
                    return true;
                }
                public synchronized int remaining() { return units; }
            }
        ''',
        'ManifestFormatter': '''
            public class ManifestFormatter {
                public String row(DispatchRequest request) {
                    return request.zone() + "," + request.units() + "," + request.fragile();
                }
                public String header() { return "zone,units,fragile"; }
            }
        ''',
        'CapacityForecast': '''
            public class CapacityForecast {
                public int expected(int current, int incoming, int departures) {
                    return Math.max(0, Math.addExact(current, incoming) - departures);
                }
                public boolean overload(int predicted, int maximum) { return predicted > maximum; }
            }
        ''',
        'ServiceHealth': '''
            public record ServiceHealth(boolean database, boolean queue, long lagMillis) {
                public boolean healthy() { return database && queue && lagMillis < 5000; }
                public String status() { return healthy() ? "UP" : "DEGRADED"; }
            }
        ''',
        'MetricWindow': '''
            public class MetricWindow {
                public double average(long total, long count) { return count == 0 ? 0 : (double) total / count; }
                public double errorRate(long failures, long requests) { return average(failures, requests); }
                public boolean alert(long failures, long requests) { return requests >= 20 && errorRate(failures, requests) > 0.05; }
            }
        ''',
        'IdempotencyKey': '''
            public class IdempotencyKey {
                public String combine(String tenant, String externalId) {
                    if (tenant.contains(":")) throw new IllegalArgumentException("tenant separator");
                    return tenant + ":" + externalId;
                }
                public boolean matches(String key, String tenant) { return key.startsWith(tenant + ":"); }
            }
        ''',
        'CredentialMask': '''
            public class CredentialMask {
                public String mask(String value) {
                    if (value == null || value.length() < 4) return "****";
                    return "****" + value.substring(value.length() - 4);
                }
            }
        ''',
        'RateWindow': '''
            public class RateWindow {
                public long bucket(long timestampMillis, long windowMillis) {
                    if (windowMillis <= 0) throw new IllegalArgumentException("window");
                    return Math.floorDiv(timestampMillis, windowMillis);
                }
                public boolean permits(int used, int limit) { return used >= 0 && used < limit; }
            }
        ''',
        'TenantLimits': '''
            public record TenantLimits(int dailyUnits, int concurrentRequests) {
                public boolean permits(int units, int active) { return units <= dailyUnits && active < concurrentRequests; }
                public TenantLimits scaled(int multiplier) {
                    return new TenantLimits(Math.multiplyExact(dailyUnits, multiplier), concurrentRequests);
                }
            }
        ''',
        'AuditLine': '''
            public record AuditLine(long timestamp, String action, String reference) {
                public String encode() { return timestamp + "\\t" + action.replace("\\t", " ") + "\\t" + reference; }
                public boolean refersTo(String target) { return reference.equals(target); }
            }
        ''',
        'QueueDrain': '''
            public class QueueDrain {
                public <T> java.util.List<T> take(java.util.Queue<T> queue, int limit) {
                    java.util.List<T> result = new java.util.ArrayList<>();
                    while (result.size() < limit && !queue.isEmpty()) result.add(queue.remove());
                    return result;
                }
            }
        ''',
        'Pagination': '''
            public class Pagination {
                public int offset(int page, int size) {
                    if (page < 0 || size <= 0) throw new IllegalArgumentException("page");
                    return Math.multiplyExact(page, size);
                }
                public int limit(int requested) { return Math.max(1, Math.min(100, requested)); }
            }
        ''',
        'FilenamePolicy': '''
            public class FilenamePolicy {
                public boolean safe(String name) {
                    return name != null && !name.isBlank() && !name.contains("/") && !name.contains("\\\\") && !name.equals("..");
                }
                public String extension(String name) { return name.substring(name.lastIndexOf('.') + 1); }
            }
        ''',
        'RetentionPolicy': '''
            public class RetentionPolicy {
                public boolean expired(long created, long now, long retainMillis) {
                    return now >= created && now - created > retainMillis;
                }
                public long archiveAfterDays(int days) { return Math.multiplyExact((long) days, 86400000L); }
            }
        ''',
    }
    for name, source in sources.items():
        write(root, JAVA_PREFIX + name + '.java', 'package relay;\n\n' + textwrap.dedent(source).strip())


def polyglot_corpus(root):
    modules = [
        ('dispatch/retry', '''
            def resolve(attempt: int, cancelled: bool, retry_after: int) -> str:
                if cancelled:
                    return 'cancelled'
                if attempt >= 4:
                    return 'exhausted'
                return 'postponed' if retry_after > 0 else 'retry'

            def delay(retry_after: int) -> int:
                return min(300, max(0, retry_after))
        ''', '''
            export function resolve(attempt: number, cancelled: boolean, retryAfter: number): string {
                if (cancelled) return 'cancelled';
                if (attempt >= 4) return 'exhausted';
                return retryAfter > 0 ? 'postponed' : 'retry';
            }
            export function delay(retryAfter: number): number {
                return Math.min(300, Math.max(0, retryAfter));
            }
        '''),
        ('dispatch/preview', '''
            def resolve(attempt: int, cancelled: bool, retry_after: int) -> str:
                return 'draft-cancelled' if cancelled else 'draft'

            def visible(attempt: int) -> bool:
                return attempt >= 0
        ''', '''
            export function resolve(attempt: number, cancelled: boolean, retryAfter: number): string {
                return cancelled ? 'draft-cancelled' : 'draft';
            }
            export function visible(attempt: number): boolean { return attempt >= 0; }
        '''),
        ('reports/retry', '''
            def resolve(attempt: int, cancelled: bool, retry_after: int) -> str:
                if cancelled:
                    return 'cancelled'
                return 'exhausted' if attempt >= 3 else 'retry'

            def bucket(attempt: int) -> str:
                return 'many' if attempt >= 4 else 'few'
        ''', '''
            export function resolve(attempt: number, cancelled: boolean, retryAfter: number): string {
                if (cancelled) return 'cancelled';
                return attempt >= 3 ? 'exhausted' : 'retry';
            }
            export function bucket(attempt: number): string { return attempt >= 4 ? 'many' : 'few'; }
        '''),
        ('admin/retry', '''
            def resolve(attempt: int, cancelled: bool, retry_after: int) -> str:
                if attempt >= 4:
                    return 'exhausted'
                return 'cancelled' if cancelled else 'retry'

            def reset() -> int:
                return 0
        ''', '''
            export function resolve(attempt: number, cancelled: boolean, retryAfter: number): string {
                if (attempt >= 4) return 'exhausted';
                return cancelled ? 'cancelled' : 'retry';
            }
            export function reset(): number { return 0; }
        '''),
        ('billing/refund', '''
            def resolve(paid: int, processing: int, dispatched: bool) -> int:
                return 0 if dispatched else max(0, paid - processing)

            def eligible(delivered_days_ago: int) -> bool:
                return 0 <= delivered_days_ago <= 30
        ''', '''
            export function resolve(paid: number, processing: number, dispatched: boolean): number {
                return dispatched ? 0 : Math.max(0, paid - processing);
            }
            export function eligible(days: number): boolean { return days >= 0 && days <= 30; }
        '''),
        ('dispatch/packing', '''
            def parcels(units: int, fragile: bool) -> int:
                limit = 5 if fragile else 8
                return (units + limit - 1) // limit

            def material(fragile: bool) -> str:
                return 'foam' if fragile else 'paper'
        ''', '''
            export function parcels(units: number, fragile: boolean): number {
                return Math.ceil(units / (fragile ? 5 : 8));
            }
            export function material(fragile: boolean): string { return fragile ? 'foam' : 'paper'; }
        '''),
        ('dispatch/zones', '''
            def normalize(zone: str) -> str:
                zone = zone.strip().lower()
                return {'zone-a': 'inland', 'zone-b': 'coast'}.get(zone, zone)

            def supported(zone: str) -> bool:
                return normalize(zone) in ('inland', 'coast')
        ''', '''
            export function normalize(zone: string): string {
                zone = zone.trim().toLowerCase();
                return zone === 'zone-a' ? 'inland' : zone === 'zone-b' ? 'coast' : zone;
            }
            export function supported(zone: string): boolean { return ['inland', 'coast'].includes(normalize(zone)); }
        '''),
        ('tracking/codes', '''
            def depot(code: str) -> str:
                return code.split('-', 1)[0]

            def valid(code: str) -> bool:
                return '-' in code and len(depot(code)) > 0
        ''', '''
            export function depot(code: string): string { return code.split('-', 1)[0]; }
            export function valid(code: string): boolean { return code.includes('-') && depot(code).length > 0; }
        '''),
        ('tracking/events', '''
            def latest(events: list[tuple[int, str]]) -> str:
                return max(events)[1] if events else 'unknown'

            def terminal(status: str) -> bool:
                return status in ('delivered', 'returned')
        ''', '''
            export function latest(events: Array<[number, string]>): string {
                return events.length ? [...events].sort((a, b) => b[0] - a[0])[0][1] : 'unknown';
            }
            export function terminal(status: string): boolean { return ['delivered', 'returned'].includes(status); }
        '''),
        ('billing/fees', '''
            def shipping(grams: int, express: bool) -> int:
                if grams < 0:
                    raise ValueError('weight')
                return 250 + grams // 500 * 35 + (400 if express else 0)

            def prepaid(credit: int, fee: int) -> bool:
                return credit >= fee
        ''', '''
            export function shipping(grams: number, express: boolean): number {
                if (grams < 0) throw new Error('weight');
                return 250 + Math.floor(grams / 500) * 35 + (express ? 400 : 0);
            }
            export function prepaid(credit: number, fee: number): boolean { return credit >= fee; }
        '''),
        ('billing/currency', '''
            def format_cents(cents: int) -> str:
                return f'{cents / 100:.2f}'

            def currency(code: str) -> str:
                return code.strip().upper()
        ''', '''
            export function formatCents(cents: number): string { return (cents / 100).toFixed(2); }
            export function currency(code: string): string { return code.trim().toUpperCase(); }
        '''),
        ('addresses/normalize', '''
            def postal_code(value: str) -> str:
                return value.replace(' ', '').upper()

            def street(value: str) -> str:
                return ' '.join(value.split())
        ''', '''
            export function postalCode(value: string): string { return value.replaceAll(' ', '').toUpperCase(); }
            export function street(value: string): string { return value.trim().replace(/\\s+/g, ' '); }
        '''),
        ('addresses/validate', '''
            def valid(street: str, postal_code: str) -> bool:
                return bool(street.strip()) and len(postal_code) >= 4

            def needs_unit(street: str) -> bool:
                return 'Apartment' in street
        ''', '''
            export function valid(street: string, postalCode: string): boolean {
                return street.trim().length > 0 && postalCode.length >= 4;
            }
            export function needsUnit(street: string): boolean { return street.includes('Apartment'); }
        '''),
        ('reports/metrics', '''
            def average(total: int, count: int) -> float:
                return total / count if count else 0.0

            def alert(failures: int, requests: int) -> bool:
                return requests >= 20 and average(failures, requests) > 0.05
        ''', '''
            export function average(total: number, count: number): number { return count ? total / count : 0; }
            export function alert(failures: number, requests: number): boolean {
                return requests >= 20 && average(failures, requests) > 0.05;
            }
        '''),
        ('reports/manifest', '''
            def row(zone: str, units: int, fragile: bool) -> str:
                return f'{zone},{units},{str(fragile).lower()}'

            def header() -> str:
                return 'zone,units,fragile'
        ''', '''
            export function row(zone: string, units: number, fragile: boolean): string {
                return `${zone},${units},${fragile}`;
            }
            export function header(): string { return 'zone,units,fragile'; }
        '''),
        ('security/masking', '''
            def mask(value: str) -> str:
                return '****' + value[-4:] if len(value) >= 4 else '****'

            def public_reference(value: str) -> str:
                return value.split(':', 1)[0]
        ''', '''
            export function mask(value: string): string { return value.length >= 4 ? '****' + value.slice(-4) : '****'; }
            export function publicReference(value: string): string { return value.split(':', 1)[0]; }
        '''),
        ('security/tenants', '''
            def key(tenant: str, external: str) -> str:
                if ':' in tenant:
                    raise ValueError('tenant separator')
                return tenant + ':' + external

            def belongs(value: str, tenant: str) -> bool:
                return value.startswith(tenant + ':')
        ''', '''
            export function key(tenant: string, external: string): string {
                if (tenant.includes(':')) throw new Error('tenant separator');
                return tenant + ':' + external;
            }
            export function belongs(value: string, tenant: string): boolean { return value.startsWith(tenant + ':'); }
        '''),
        ('storage/pagination', '''
            def offset(page: int, size: int) -> int:
                if page < 0 or size <= 0:
                    raise ValueError('page')
                return page * size

            def limit(requested: int) -> int:
                return max(1, min(100, requested))
        ''', '''
            export function offset(page: number, size: number): number {
                if (page < 0 || size <= 0) throw new Error('page');
                return page * size;
            }
            export function limit(requested: number): number { return Math.max(1, Math.min(100, requested)); }
        '''),
        ('storage/retention', '''
            def expired(created: int, now: int, retention: int) -> bool:
                return now >= created and now - created > retention

            def archive_after(days: int) -> int:
                return days * 86400
        ''', '''
            export function expired(created: number, now: number, retention: number): boolean {
                return now >= created && now - created > retention;
            }
            export function archiveAfter(days: number): number { return days * 86400; }
        '''),
        ('dispatch/windows', '''
            def contains(start: int, end: int, hour: int) -> bool:
                return start <= hour < end

            def overlaps(start: int, end: int, other_start: int, other_end: int) -> bool:
                return start < other_end and other_start < end
        ''', '''
            export function contains(start: number, end: number, hour: number): boolean { return start <= hour && hour < end; }
            export function overlaps(start: number, end: number, otherStart: number, otherEnd: number): boolean {
                return start < otherEnd && otherStart < end;
            }
        '''),
    ]
    for name, py, ts in modules:
        write(root, 'workers/' + name + '.py', py)
        write(root, 'web/' + name + '.ts', ts)


def create_task(task_id, directory):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    root = Path(directory)
    if root.exists() and any(root.iterdir()):
        raise ValueError('task directory must be empty')
    root.mkdir(parents=True, exist_ok=True)
    if task_id == 'polyglot_retry':
        polyglot_corpus(root)
        prompt = (
            'Locate the TypeScript and Python live dispatch retry functions. The required policy '
            'checks cancellation first, then exhausts attempts at four or higher, then postpones '
            'a positive server retry delay; otherwise it retries. Preview, admin and reporting '
            'variants differ. Give each function as relative/path:symbol and its returned value '
            'for attempt=3, cancelled=false, server retry delay=200. Do not modify files. Reply '
            'with exactly one JSON object with string fields typescript_symbol, python_symbol, '
            'typescript_result and python_result.')
    else:
        java_corpus(root)
        if task_id == 'java_dispatch':
            prompt = (
                'For relay.DispatchService.dispatch(new DispatchRequest("zone-b", 11, true, false)), '
                'follow the real routing and packing/capacity calculations. Return the resulting '
                'outcome, destination, and final Class.method that constructs that DispatchDecision '
                '(without its package prefix). Do not modify files. Reply with exactly one JSON '
                'object with string fields outcome, destination and sink.')
        else:
            prompt = (
                'Fix relay.BackoffPolicy.delayMillis so every valid input returns '
                'min(capMillis, baseMillis * 2^attempt) without integer overflow or shift wraparound. '
                'Valid attempts are 0 through 62 inclusive; baseMillis and capMillis are nonnegative '
                'Java ints. Preserve IllegalArgumentException for invalid inputs and the behavior '
                'of deadline and immediate. Modify only ' + REPAIR_FILE + '. You may compile and '
                'run probes inside this workspace. '
                'Reply with exactly one JSON object with string fields changed_file and status; '
                'status should be "fixed" only when the change is complete.')
    prompt += ' Put any temporary build or probe files under .scratch; do not delete files.'
    write(root, 'TASK.md', prompt)
    data = contents(root)
    return {
        'task_id': task_id, 'prompt': prompt,
        'response_schema': {'type': 'object', 'required': list(FIELDS[task_id]),
                            'additionalProperties': False,
                            'properties': {field: {'type': 'string'} for field in FIELDS[task_id]}},
        'writable': task_id == 'java_backoff', 'workspace_files': list(data),
        'manifest_sha256': manifest(root),
        'source_bytes': sum(len(value) for name, value in data.items() if name != 'TASK.md'),
        'source_lines': sum(value.count(b'\n') for name, value in data.items() if name != 'TASK.md'),
    }


def _repair_probe():
    return '''
        import relay.BackoffPolicy;
        import java.math.BigInteger;
        public class ExternalProbe {
            public static void main(String[] args) {
                int[] bases = {0, 1, 7, 1000, 1073741824, 2000000000, Integer.MAX_VALUE};
                int[] caps = {0, 1, 9, 30000, 1073741824, Integer.MAX_VALUE};
                int checked = 0;
                for (int attempt = 0; attempt <= 62; attempt++) {
                    for (int base : bases) for (int cap : caps) {
                        int expected = BigInteger.valueOf(base).shiftLeft(attempt)
                            .min(BigInteger.valueOf(cap)).intValueExact();
                        if (BackoffPolicy.delayMillis(attempt, base, cap) != expected)
                            throw new AssertionError("saturating backoff");
                        if (BackoffPolicy.deadline(100L, attempt, base, cap) != 100L + expected)
                            throw new AssertionError("deadline");
                        if (BackoffPolicy.immediate(attempt, base, cap) != (expected == 0))
                            throw new AssertionError("immediate");
                        checked += 3;
                    }
                }
                int[][] invalid = {{-1,1,1}, {63,1,1}, {0,-1,1}, {0,1,-1},
                                   {Integer.MIN_VALUE,1,1}, {Integer.MAX_VALUE,1,1}};
                for (int[] row : invalid) {
                    try {
                        BackoffPolicy.delayMillis(row[0], row[1], row[2]);
                        throw new AssertionError("invalid input accepted");
                    } catch (IllegalArgumentException expected) { checked++; }
                    try {
                        BackoffPolicy.deadline(100L, row[0], row[1], row[2]);
                        throw new AssertionError("invalid deadline input accepted");
                    } catch (IllegalArgumentException expected) { checked++; }
                    try {
                        BackoffPolicy.immediate(row[0], row[1], row[2]);
                        throw new AssertionError("invalid immediate input accepted");
                    } catch (IllegalArgumentException expected) { checked++; }
                }
                try {
                    BackoffPolicy.deadline(Long.MAX_VALUE, 0, 1, 1);
                    throw new AssertionError("deadline overflow accepted");
                } catch (ArithmeticException expected) { checked++; }
                System.out.print(checked);
            }
        }
    '''


def evaluate(task_id, directory, final_text):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    try:
        answer = json.loads(final_text)
    except (ValueError, TypeError):
        return {'passed': False, 'details': 'invalid JSON'}
    if (not isinstance(answer, dict) or set(answer) != set(FIELDS[task_id])
            or any(type(value) is not str for value in answer.values())):
        return {'passed': False, 'details': 'wrong response shape'}
    root = Path(directory)
    if root.is_symlink() or any(path.is_symlink() for path in root.rglob('*')):
        return {'passed': False, 'details': 'symlinked workspace path'}
    with tempfile.TemporaryDirectory(prefix='graphharness-fresh-eval-') as temporary:
        original = Path(temporary) / 'original'
        create_task(task_id, original)
        before, after = contents(original), contents(root)
        if set(before) != set(after):
            return {'passed': False, 'details': 'unexpected or missing workspace files'}
        changed = [name for name in before if before[name] != after[name]]
        allowed = [REPAIR_FILE] if task_id == 'java_backoff' else []
        if changed != allowed:
            return {'passed': False, 'details': 'wrong change scope', 'changed_files': changed}
        if task_id == 'java_dispatch':
            passed = answer == {'outcome': 'REVIEW', 'destination': 'coast-manual',
                                'sink': 'ReviewLedger.recordDeferred'}
            return {'passed': passed, 'details': 'exact branch, destination and sink', 'changed_files': changed}
        if task_id == 'polyglot_retry':
            passed = answer == {'typescript_symbol': 'web/dispatch/retry.ts:resolve',
                                'python_symbol': 'workers/dispatch/retry.py:resolve',
                                'typescript_result': 'postponed', 'python_result': 'postponed'}
            return {'passed': passed, 'details': 'exact file, symbol and evaluated outputs', 'changed_files': changed}
        if answer != {'changed_file': REPAIR_FILE, 'status': 'fixed'}:
            return {'passed': False, 'details': 'repair not correctly reported'}
        tested = Path(temporary) / 'tested'
        tested.mkdir()
        for name, data in after.items():
            target = tested / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        write(tested, 'ExternalProbe.java', _repair_probe())
        build = Path(temporary) / 'classes'
        build.mkdir()
        try:
            compiled = subprocess.run(
                ['javac', '-d', str(build), *map(str, sorted(tested.rglob('*.java')))],
                capture_output=True, timeout=45)
            executed = None if compiled.returncode else subprocess.run(
                ['java', '-cp', str(build), 'ExternalProbe'], capture_output=True, timeout=10)
        except subprocess.TimeoutExpired:
            return {'passed': False, 'details': 'external evaluator compile or execution timeout',
                    'changed_files': changed}
        output = compiled.stdout + compiled.stderr
        if executed is not None:
            output += executed.stdout + executed.stderr
        return {
            'passed': executed is not None and executed.returncode == 0 and executed.stdout == b'7957',
            'details': 'external javac/java and 7957 BigInteger-reference behavior assertions',
            'changed_files': changed, 'compile_exit': compiled.returncode,
            'test_exit': None if executed is None else executed.returncode,
            'test_output_sha256': hashlib.sha256(output).hexdigest(),
            'diff_sha256': hashlib.sha256(after[REPAIR_FILE]).hexdigest(),
        }
