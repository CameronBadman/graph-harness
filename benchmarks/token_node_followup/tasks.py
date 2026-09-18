from __future__ import annotations

import hashlib
import json
from pathlib import Path
import secrets
import subprocess
import tempfile
import textwrap

TASK_IDS = ('java_short', 'java_long', 'java_overload')
JAVA_PREFIX = 'src/main/java/studio/'
TARGETS = {
    'java_short': ('DayWindow.java', 'public static boolean contains(int start, int end, int minute)'),
    'java_long': ('NoticeRenderer.java', 'public static String render(Notice notice, NoticeOptions options)'),
    'java_overload': ('PermissionGate.java', 'public static boolean permits(AccessRequest request, PermissionTable table)'),
}
FIELDS = ('changed_file', 'status')


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
        'DayWindow': '''
            public class DayWindow {
                public static boolean contains(int start, int end, int minute) {
                    if (start < 0 || start >= 1440 || end < 0 || end >= 1440 || minute < 0 || minute >= 1440)
                        throw new IllegalArgumentException("minute outside day");
                    return start <= minute && minute < end;
                }
                public static int duration(int start, int end) {
                    if (start < 0 || start >= 1440 || end < 0 || end >= 1440)
                        throw new IllegalArgumentException("minute outside day");
                    return Math.floorMod(end - start, 1440);
                }
            }
        ''',
        'TimeRange': '''
            public record TimeRange(int start, int end) {
                public TimeRange {
                    if (start < 0 || start >= 1440 || end < 0 || end >= 1440)
                        throw new IllegalArgumentException("minute outside day");
                }
                public boolean contains(int minute) { return DayWindow.contains(start, end, minute); }
                public int minutes() { return DayWindow.duration(start, end); }
            }
        ''',
        'WorkingHours': '''
            public record WorkingHours(TimeRange weekdays, TimeRange weekend) {
                public boolean open(int weekday, int minute) {
                    if (weekday < 1 || weekday > 7) throw new IllegalArgumentException("weekday");
                    return (weekday >= 6 ? weekend : weekdays).contains(minute);
                }
                public int weeklyMinutes() { return weekdays.minutes() * 5 + weekend.minutes() * 2; }
            }
        ''',
        'DailyAgenda': '''
            public class DailyAgenda {
                private final java.util.List<Session> sessions = new java.util.ArrayList<>();
                public void add(Session session) { sessions.add(java.util.Objects.requireNonNull(session)); }
                public java.util.List<Session> active(int minute) {
                    return sessions.stream().filter(session -> session.window().contains(minute)).toList();
                }
                public int count() { return sessions.size(); }
            }
        ''',
        'Session': '''
            public record Session(String title, String roomId, TimeRange window, SessionStatus status, int capacity) {
                public Session {
                    if (title == null || roomId == null || window == null || status == null || capacity < 0)
                        throw new IllegalArgumentException("session");
                }
                public boolean reservable() { return status == SessionStatus.OPEN && capacity > 0; }
            }
        ''',
        'SessionStatus': '''
            public enum SessionStatus {
                DRAFT, OPEN, FULL, CANCELLED;
                public boolean visible() { return this == OPEN || this == FULL; }
                public boolean terminal() { return this == CANCELLED; }
            }
        ''',
        'Room': '''
            public record Room(String id, String name, int capacity, WorkingHours hours) {
                public Room {
                    if (id == null || name == null || hours == null || capacity < 1)
                        throw new IllegalArgumentException("room");
                }
                public boolean accommodates(int people) { return people >= 0 && people <= capacity; }
            }
        ''',
        'RoomDirectory': '''
            public class RoomDirectory {
                private final java.util.Map<String, Room> rooms = new java.util.LinkedHashMap<>();
                public void register(Room room) { rooms.put(room.id(), room); }
                public Room find(String id) { return rooms.get(id); }
                public java.util.List<Room> open(int weekday, int minute) {
                    return rooms.values().stream().filter(room -> room.hours().open(weekday, minute)).toList();
                }
            }
        ''',
        'AgendaService': '''
            public class AgendaService {
                public static boolean canReserve(Session session, Room room, int weekday, int minute) {
                    return session.reservable() && room.id().equals(session.roomId())
                        && room.accommodates(session.capacity()) && room.hours().open(weekday, minute)
                        && session.window().contains(minute);
                }
                public static String location(Session session, RoomDirectory directory) {
                    Room room = directory.find(session.roomId());
                    return room == null ? "unassigned" : room.name();
                }
            }
        ''',
        'Notice': '''
            public record Notice(String topic, String sender, String message, int seats,
                                 boolean urgent, java.util.List<String> labels) {
                public Notice withSeats(int remaining) {
                    return new Notice(topic, sender, message, remaining, urgent, labels);
                }
            }
        ''',
        'NoticeOptions': '''
            public record NoticeOptions(boolean includeSender, boolean includeLabels, boolean compact, int maxLabels) {
                public static NoticeOptions screen() { return new NoticeOptions(true, true, false, 3); }
                public static NoticeOptions summary() { return new NoticeOptions(false, false, true, 0); }
            }
        ''',
        'NoticeEscaper': r'''
            public class NoticeEscaper {
                public static String escape(String value) {
                    StringBuilder escaped = new StringBuilder();
                    for (int i = 0; i < value.length(); i++) {
                        char current = value.charAt(i);
                        switch (current) {
                            case '\\' -> escaped.append("\\\\");
                            case '|' -> escaped.append("\\|");
                            case ',' -> escaped.append("\\,");
                            case '\n' -> escaped.append("\\n");
                            case '\r' -> escaped.append("\\r");
                            default -> escaped.append(current);
                        }
                    }
                    return escaped.toString();
                }
                public static boolean multiline(String value) { return value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0; }
            }
        ''',
        'NoticeRenderer': '''
            public class NoticeRenderer {
                public static String render(Notice notice, NoticeOptions options) {
                    if (notice == null || options == null)
                        throw new IllegalArgumentException("notice and options are required");
                    if (notice.topic() == null || notice.sender() == null || notice.message() == null)
                        throw new IllegalArgumentException("notice fields are required");
                    if (notice.labels() == null || notice.seats() < 0)
                        throw new IllegalArgumentException("invalid notice details");
                    if (options.maxLabels() < 0 || options.maxLabels() > 5)
                        throw new IllegalArgumentException("maxLabels must be between zero and five");
                    for (String label : notice.labels()) {
                        if (label == null) throw new IllegalArgumentException("null label");
                    }
                    String separator = options.compact() ? "|" : " | ";
                    StringBuilder result = new StringBuilder();
                    result.append(notice.urgent() ? "ALERT" : "NOTE");
                    result.append(separator);
                    result.append(NoticeEscaper.escape(notice.topic()));
                    if (options.includeSender()) {
                        result.append(separator);
                        result.append("from=");
                        result.append(NoticeEscaper.escape(notice.sender()));
                    }
                    result.append(separator);
                    result.append("message=");
                    result.append(notice.message());
                    result.append(separator);
                    result.append("seats=");
                    if (notice.seats() == 0) {
                        result.append("full");
                    } else if (notice.seats() == 1) {
                        result.append("1 remaining");
                    } else {
                        result.append(notice.seats()).append(" remaining");
                    }
                    if (options.includeLabels() && !notice.labels().isEmpty()) {
                        result.append(separator);
                        result.append("labels=");
                        int shown = Math.min(options.maxLabels(), notice.labels().size());
                        for (int i = 0; i < shown; i++) {
                            if (i > 0) result.append(',');
                            result.append(NoticeEscaper.escape(notice.labels().get(i)));
                        }
                        int hidden = notice.labels().size() - shown;
                        if (hidden > 0) {
                            if (shown > 0) result.append(',');
                            result.append('+').append(hidden);
                        }
                    }
                    return result.toString();
                }
                public static String title(Notice notice) {
                    return (notice.urgent() ? "! " : "") + notice.topic();
                }
            }
        ''',
        'NoticeChannel': '''
            public enum NoticeChannel {
                SCREEN, MAIL, DIGEST;
                public NoticeOptions options() {
                    return this == DIGEST ? NoticeOptions.summary() : NoticeOptions.screen();
                }
            }
        ''',
        'NoticeQueue': '''
            public class NoticeQueue {
                private final java.util.ArrayDeque<Notice> queue = new java.util.ArrayDeque<>();
                public void add(Notice notice) { queue.add(java.util.Objects.requireNonNull(notice)); }
                public String take(NoticeChannel channel) {
                    Notice next = queue.poll();
                    return next == null ? "" : NoticeRenderer.render(next, channel.options());
                }
                public int pending() { return queue.size(); }
            }
        ''',
        'Participant': '''
            public record Participant(String name, String role, boolean serviceAccount) {
                public Participant {
                    if (name == null || role == null) throw new IllegalArgumentException("participant");
                }
                public AccessRequest request(int actionCode, boolean archived) {
                    return new AccessRequest(role, actionCode, serviceAccount, archived);
                }
            }
        ''',
        'ParticipantList': '''
            public class ParticipantList {
                private final java.util.Map<String, Participant> people = new java.util.LinkedHashMap<>();
                public void add(Participant participant) { people.put(participant.name(), participant); }
                public java.util.List<String> names() { return java.util.List.copyOf(people.keySet()); }
                public long humanCount() { return people.values().stream().filter(p -> !p.serviceAccount()).count(); }
            }
        ''',
        'AccessRequest': '''
            public record AccessRequest(String role, int actionCode, boolean serviceAccount, boolean archived) {
                public AccessRequest forAction(int action) {
                    return new AccessRequest(role, action, serviceAccount, archived);
                }
            }
        ''',
        'ActionCodes': '''
            public class ActionCodes {
                public static final int VIEW = 10;
                public static final int EDIT = 20;
                public static final int PUBLISH = 30;
                public static final int ADMINISTER = 40;
                public static int requiredMask(int code) {
                    return switch (code) { case VIEW -> 1; case EDIT -> 2; case PUBLISH -> 3; case ADMINISTER -> 4; default -> 0; };
                }
                public static String label(int code) {
                    return switch (code) { case VIEW -> "view"; case EDIT -> "edit"; case PUBLISH -> "publish"; case ADMINISTER -> "administer"; default -> "unknown"; };
                }
            }
        ''',
        'PermissionTable': '''
            public class PermissionTable {
                private final java.util.Map<String, Integer> grants;
                public PermissionTable(java.util.Map<String, Integer> grants) {
                    if (grants == null || grants.values().stream().anyMatch(v -> v == null || v < 0 || v > 7))
                        throw new IllegalArgumentException("permission masks");
                    this.grants = java.util.Map.copyOf(grants);
                }
                public int grantedMask(String role, boolean serviceAccount, boolean archived) {
                    int mask = role == null ? 0 : grants.getOrDefault(role, 0);
                    if (serviceAccount) mask &= 3;
                    if (archived) mask &= 1;
                    return mask;
                }
                public static PermissionTable defaults() {
                    return new PermissionTable(java.util.Map.of("guest", 1, "editor", 3, "owner", 7));
                }
            }
        ''',
        'PermissionGate': '''
            public class PermissionGate {
                public static boolean permits(int grantedMask, int requiredMask) {
                    if (grantedMask < 0 || grantedMask > 7 || requiredMask <= 0 || requiredMask > 7) return false;
                    return (grantedMask & requiredMask) == requiredMask;
                }
                public static boolean permits(AccessRequest request, PermissionTable table) {
                    if (request == null || table == null) return false;
                    int granted = table.grantedMask(request.role(), request.serviceAccount(), request.archived());
                    return permits(granted, request.actionCode());
                }
                public static String decision(AccessRequest request, PermissionTable table) {
                    return permits(request, table) ? "allowed" : "denied";
                }
            }
        ''',
        'WorkshopService': '''
            public class WorkshopService {
                private final PermissionTable permissions;
                private final AuditLog log;
                public WorkshopService(PermissionTable permissions, AuditLog log) {
                    this.permissions = permissions;
                    this.log = log;
                }
                public boolean publish(Participant author, Session session) {
                    AccessRequest request = author.request(ActionCodes.PUBLISH, session.status() == SessionStatus.CANCELLED);
                    boolean permitted = PermissionGate.permits(request, permissions);
                    log.append(new AuditEntry(author.name(), "publish", session.title(), permitted));
                    return permitted;
                }
            }
        ''',
        'AuditEntry': '''
            public record AuditEntry(String actor, String action, String subject, boolean permitted) {
                public String line() {
                    return NoticeEscaper.escape(actor) + "|" + NoticeEscaper.escape(action) + "|"
                        + NoticeEscaper.escape(subject) + "|" + (permitted ? "allow" : "deny");
                }
            }
        ''',
        'AuditLog': '''
            public class AuditLog {
                private final java.util.List<AuditEntry> entries = new java.util.ArrayList<>();
                public void append(AuditEntry entry) { entries.add(java.util.Objects.requireNonNull(entry)); }
                public java.util.List<String> lines() { return entries.stream().map(AuditEntry::line).toList(); }
                public long denied() { return entries.stream().filter(entry -> !entry.permitted()).count(); }
            }
        ''',
    }
    for name, source in sources.items():
        write(root, JAVA_PREFIX + name + '.java', 'package studio;\n\n' + textwrap.dedent(source).strip())


def create_task(task_id, directory):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    root = Path(directory)
    if root.exists() and any(root.iterdir()):
        raise ValueError('task directory must be empty')
    root.mkdir(parents=True, exist_ok=True)
    java_corpus(root)
    prompts = {
        'java_short': (
            'Fix studio.DayWindow.contains(int start, int end, int minute). It tests a half-open '
            'daily window [start, end) on a 1440-minute clock. When start is greater than end, '
            'the window crosses midnight; equal endpoints represent an empty window. Every '
            'argument must be between 0 and 1439, with IllegalArgumentException otherwise. '
            'Keep start inclusive, end exclusive, and preserve duration and other callers.'),
        'java_long': (
            'Fix studio.NoticeRenderer.render(Notice notice, NoticeOptions options). Message '
            'text is currently copied into the output without the same escaping used for the '
            'other text fields. Make every message pass through the existing NoticeEscaper '
            'policy exactly once, including empty text, literal backslashes, delimiters, '
            'newlines, carriage returns and Unicode. Preserve all existing validation, '
            'formatting, labels, urgency, seat counts and option-dependent behavior.'),
        'java_overload': (
            'Fix the studio.PermissionGate.permits(AccessRequest request, PermissionTable table) '
            'overload. Request action codes identify operations; they are not permission masks. '
            'Use the operation requirements defined by ActionCodes and the effective grants '
            'from PermissionTable, including service-account and archived restrictions. '
            'An operation is permitted only when all its required permissions are present. '
            'Unknown operations, absent roles and null request/table must be denied. Preserve '
            'the raw-mask permits(int, int) overload and decision behavior.'),
    }
    path = JAVA_PREFIX + TARGETS[task_id][0]
    prompt = (prompts[task_id] + ' Change only the body of this method in ' + path +
              '; preserve all bytes outside its braces, including other overloads and methods. '
              'You may compile and run probes; put every temporary or build file under .scratch. '
              'Do not delete files. Reply with exactly one JSON object with string fields '
              'changed_file and status; status should be "fixed" only when the repair is complete.')
    write(root, 'TASK.md', prompt)
    data = contents(root)
    return {
        'task_id': task_id, 'prompt': prompt, 'writable': True,
        'response_schema': {'type': 'object', 'required': list(FIELDS), 'additionalProperties': False,
                            'properties': {field: {'type': 'string'} for field in FIELDS}},
        'workspace_files': list(data), 'manifest_sha256': manifest(root),
        'source_bytes': sum(len(value) for name, value in data.items() if name.endswith('.java')),
        'source_lines': sum(value.count(b'\n') for name, value in data.items() if name.endswith('.java')),
    }


def body_span(source, signature):
    start = source.index(signature.encode()) + len(signature.encode())
    opening = source.index(b'{', start)
    index, depth, state = opening + 1, 1, 'code'
    while index < len(source):
        char = source[index:index + 1]
        pair = source[index:index + 2]
        if state == 'line':
            if char in (b'\r', b'\n'):
                state = 'code'
        elif state == 'block':
            if pair == b'*/':
                state, index = 'code', index + 1
        elif state in ('string', 'char'):
            if char == b'\\':
                index += 1
            elif char == (b'"' if state == 'string' else b"'"):
                state = 'code'
        elif state == 'text':
            if char == b'\\':
                index += 1
            elif source[index:index + 3] == b'"""':
                state, index = 'code', index + 2
        elif pair == b'//':
            state, index = 'line', index + 1
        elif pair == b'/*':
            state, index = 'block', index + 1
        elif source[index:index + 3] == b'"""':
            state, index = 'text', index + 2
        elif char in (b'"', b"'"):
            state = 'string' if char == b'"' else 'char'
        elif char == b'{':
            depth += 1
        elif char == b'}':
            depth -= 1
            if depth == 0:
                return opening + 1, index
        index += 1
    raise ValueError('unclosed method body')


def _probe(task_id):
    common = '''
        import studio.*;
        import java.util.*;
        public class ExternalProbe {
            private static int checks = 0;
            private static void check(boolean value) {
                checks++;
                if (!value) throw new AssertionError("behavior check " + checks);
            }
            private static void invalid(Runnable action) {
                checks++;
                try { action.run(); } catch (IllegalArgumentException expected) { return; }
                throw new AssertionError("missing argument rejection " + checks);
            }
    '''
    if task_id == 'java_short':
        body = '''
            public static void main(String[] args) {
                int[][] windows = {{0,0},{1,1},{1439,1439},{0,1},{0,1439},{1,1439},{1,0},
                    {1439,0},{1439,1},{1380,120},{480,1020},{720,721},{721,720},{120,1380}};
                for (int[] window : windows) {
                    int length = Math.floorMod(window[1] - window[0], 1440);
                    for (int minute = 0; minute < 1440; minute++) {
                        int elapsed = Math.floorMod(minute - window[0], 1440);
                        check(DayWindow.contains(window[0], window[1], minute) == (elapsed < length));
                    }
                    check(DayWindow.duration(window[0], window[1]) == length);
                }
                for (int bad : new int[]{Integer.MIN_VALUE, -1440, -1, 1440, 1441, Integer.MAX_VALUE}) {
                    invalid(() -> DayWindow.contains(bad, 700, 800));
                    invalid(() -> DayWindow.contains(600, bad, 800));
                    invalid(() -> DayWindow.contains(600, 700, bad));
                }
                Random random = new Random(730421);
                for (int i = 0; i < 2000; i++) {
                    int start = random.nextInt(1440), end = random.nextInt(1440), minute = random.nextInt(1440);
                    int length = Math.floorMod(end - start, 1440);
                    check(new TimeRange(start, end).contains(minute) == (Math.floorMod(minute - start, 1440) < length));
                }
                check(new TimeRange(1300, 90).contains(20));
                check(!new TimeRange(1300, 90).contains(90));
                System.out.print(args[0] + ":" + checks);
            }
        '''
        count = 22194
    elif task_id == 'java_long':
        body = r'''
            private static String referenceEscape(String text) {
                return text.replace("\\", "\\\\").replace("|", "\\|").replace(",", "\\,")
                    .replace("\n", "\\n").replace("\r", "\\r");
            }
            private static String reference(Notice n, NoticeOptions o) {
                List<String> fields = new ArrayList<>();
                fields.add(n.urgent() ? "ALERT" : "NOTE");
                fields.add(referenceEscape(n.topic()));
                if (o.includeSender()) fields.add("from=" + referenceEscape(n.sender()));
                fields.add("message=" + referenceEscape(n.message()));
                fields.add("seats=" + (n.seats() == 0 ? "full" : n.seats() + " remaining"));
                if (o.includeLabels() && !n.labels().isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    n.labels().stream().limit(o.maxLabels()).map(ExternalProbe::referenceEscape).forEach(labels::add);
                    int hidden = n.labels().size() - Math.min(o.maxLabels(), n.labels().size());
                    if (hidden > 0) labels.add("+" + hidden);
                    fields.add("labels=" + String.join(",", labels));
                }
                return String.join(o.compact() ? "|" : " | ", fields);
            }
            public static void main(String[] args) {
                String[] messages = {"", "ordinary text", "|", ",", "\\", "\n", "\r", "\r\n", "a|b,c\\d\ne\r",
                    "\\n|\\,", "musique café 🪕", "\"quote\" {value}", "a\u0000b"};
                for (String message : messages) for (boolean compact : new boolean[]{false,true})
                    for (boolean sender : new boolean[]{false,true}) for (boolean labels : new boolean[]{false,true})
                    for (boolean urgent : new boolean[]{false,true}) for (int seats : new int[]{0,1,2,31,Integer.MAX_VALUE})
                    for (int maximum : new int[]{0,1,3,5}) {
                        Notice notice = new Notice("topic|x", "sender,y", message, seats, urgent,
                            List.of("one", "two|three", "four\\five", "six\nseven"));
                        NoticeOptions options = new NoticeOptions(sender, labels, compact, maximum);
                        check(NoticeRenderer.render(notice, options).equals(reference(notice, options)));
                    }
                Notice valid = new Notice("topic", "sender", "message", 3, false, List.of());
                NoticeOptions options = NoticeOptions.screen();
                check(NoticeRenderer.render(valid, options).equals(reference(valid, options)));
                check(NoticeRenderer.title(valid).equals("topic"));
                invalid(() -> NoticeRenderer.render(null, options));
                invalid(() -> NoticeRenderer.render(valid, null));
                invalid(() -> NoticeRenderer.render(new Notice(null,"s","m",0,false,List.of()), options));
                invalid(() -> NoticeRenderer.render(new Notice("t",null,"m",0,false,List.of()), options));
                invalid(() -> NoticeRenderer.render(new Notice("t","s",null,0,false,List.of()), options));
                invalid(() -> NoticeRenderer.render(new Notice("t","s","m",0,false,null), options));
                invalid(() -> NoticeRenderer.render(new Notice("t","s","m",-1,false,List.of()), options));
                invalid(() -> NoticeRenderer.render(valid, new NoticeOptions(true,true,false,-1)));
                invalid(() -> NoticeRenderer.render(valid, new NoticeOptions(true,true,false,6)));
                invalid(() -> NoticeRenderer.render(new Notice("t","s","m",0,false,Arrays.asList((String)null)), options));
                invalid(() -> NoticeRenderer.render(new Notice("t","s","m",0,false,Arrays.asList((String)null)), NoticeOptions.summary()));
                System.out.print(args[0] + ":" + checks);
            }
        '''
        count = 4173
    else:
        body = '''
            private static boolean reference(int granted, int action) {
                return switch (action) {
                    case 10 -> granted == 1 || granted == 3 || granted == 5 || granted == 7;
                    case 20 -> granted == 2 || granted == 3 || granted == 6 || granted == 7;
                    case 30 -> granted == 3 || granted == 7;
                    case 40 -> granted >= 4;
                    default -> false;
                };
            }
            public static void main(String[] args) {
                for (int mask = 0; mask <= 7; mask++) {
                    PermissionTable table = new PermissionTable(Map.of("custom", mask));
                    for (boolean service : new boolean[]{false,true}) for (boolean archived : new boolean[]{false,true})
                        for (String role : new String[]{"custom", "absent", null}) {
                            int effective = "custom".equals(role) ? mask : 0;
                            if (service) effective %= 4;
                            if (archived) effective %= 2;
                            for (int action : new int[]{Integer.MIN_VALUE,-1,0,1,2,3,4,7,9,10,11,20,30,40,41,Integer.MAX_VALUE}) {
                                AccessRequest request = new AccessRequest(role, action, service, archived);
                                boolean expected = reference(effective, action);
                                check(PermissionGate.permits(request, table) == expected);
                                check(PermissionGate.decision(request, table).equals(expected ? "allowed" : "denied"));
                            }
                        }
                }
                for (int granted = -1; granted <= 8; granted++) for (int required = -1; required <= 8; required++) {
                    boolean expected = granted >= 0 && granted <= 7 && required > 0 && required <= 7
                        && (granted & required) == required;
                    check(PermissionGate.permits(granted, required) == expected);
                }
                check(!PermissionGate.permits((AccessRequest)null, PermissionTable.defaults()));
                check(!PermissionGate.permits(new AccessRequest("owner",10,false,false), null));
                check(!PermissionGate.permits((AccessRequest)null, null));
                AuditLog log = new AuditLog();
                WorkshopService service = new WorkshopService(PermissionTable.defaults(), log);
                Session session = new Session("ceramics", "r1", new TimeRange(540,600), SessionStatus.OPEN, 8);
                check(service.publish(new Participant("editor", "editor", false), session));
                check(!service.publish(new Participant("guest", "guest", false), session));
                check(log.denied() == 1);
                System.out.print(args[0] + ":" + checks);
            }
        '''
        count = 3178
    return common + body + '\n}\n', count


def _scope_probe():
    return r'''
        import java.nio.charset.StandardCharsets;
        import java.nio.file.*;
        import java.util.*;
        import javax.tools.*;
        import com.sun.source.tree.*;
        import com.sun.source.util.*;
        public class ScopeProbe {
            public static void main(String[] args) throws Exception {
                Path path = Path.of(args[0]);
                String source = Files.readString(path, StandardCharsets.UTF_8);
                JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
                try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                    DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
                    JavacTask task = (JavacTask)compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null,
                        files.getJavaFileObjects(path));
                    SourcePositions positions = Trees.instance(task).getSourcePositions();
                    for (CompilationUnitTree unit : task.parse()) {
                        new TreeScanner<Void, Void>() {
                            public Void visitMethod(MethodTree method, Void unused) {
                                if (method.getBody() != null) {
                                    int begin = (int)positions.getStartPosition(unit, method.getBody());
                                    int end = (int)positions.getEndPosition(unit, method.getBody());
                                    if (begin >= 0 && end > begin) {
                                        int startByte = source.substring(0, begin + 1).getBytes(StandardCharsets.UTF_8).length;
                                        int endByte = source.substring(0, end - 1).getBytes(StandardCharsets.UTF_8).length;
                                        System.out.println(startByte + ":" + endByte);
                                    }
                                }
                                return super.visitMethod(method, unused);
                            }
                        }.scan(unit, null);
                    }
                    if (diagnostics.getDiagnostics().stream().anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR))
                        throw new IllegalArgumentException("invalid parse");
                }
            }
        }
    '''


def _normalized_diagnostics(output, temporary):
    return output.replace(str(temporary).encode(), b'<evaluation>')


def evaluate(task_id, directory, final_text):
    if task_id not in TASK_IDS:
        raise ValueError('unknown task_id')
    try:
        answer = json.loads(final_text)
    except (ValueError, TypeError):
        return {'passed': False, 'details': 'invalid JSON'}
    if (not isinstance(answer, dict) or set(answer) != set(FIELDS)
            or any(type(value) is not str for value in answer.values())):
        return {'passed': False, 'details': 'wrong response shape'}
    root = Path(directory)
    if root.is_symlink() or any(path.is_symlink() for path in root.rglob('*')):
        return {'passed': False, 'details': 'symlinked workspace path'}
    target_file, signature = TARGETS[task_id]
    target = JAVA_PREFIX + target_file
    with tempfile.TemporaryDirectory(prefix='graphharness-interface-eval-') as temporary:
        original = Path(temporary) / 'original'
        create_task(task_id, original)
        before, after = contents(original), contents(root)
        if set(before) != set(after):
            return {'passed': False, 'details': 'unexpected or missing workspace files'}
        changed = [name for name in before if before[name] != after[name]]
        if changed != [target]:
            return {'passed': False, 'details': 'wrong change scope', 'changed_files': changed}
        old_start, old_end = body_span(before[target], signature)
        suffix = before[target][old_end:]
        if not after[target].startswith(before[target][:old_start]) or not after[target].endswith(suffix):
            return {'passed': False, 'details': 'bytes outside target body changed', 'changed_files': changed}
        expected_span = (str(old_start) + ':' + str(len(after[target]) - len(suffix))).encode()
        if answer != {'changed_file': target, 'status': 'fixed'}:
            return {'passed': False, 'details': 'repair not correctly reported', 'changed_files': changed}
        tested = Path(temporary) / 'tested'
        for name, data in after.items():
            destination = tested / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(data)
        probe, expected_count = _probe(task_id)
        write(tested, 'ExternalProbe.java', probe)
        write(tested, 'ScopeProbe.java', _scope_probe())
        build = Path(temporary) / 'classes'
        build.mkdir()
        nonce = secrets.token_hex(16)
        try:
            compiled = subprocess.run(
                ['javac', '-proc:none', '-d', str(build), *map(str, sorted(tested.rglob('*.java')))],
                capture_output=True, timeout=45)
            scope = None if compiled.returncode else subprocess.run(
                ['java', '-cp', str(build), 'ScopeProbe', str(tested / target)], capture_output=True, timeout=15)
            scope_valid = scope is not None and scope.returncode == 0 and expected_span in scope.stdout.splitlines()
            executed = None if not scope_valid else subprocess.run(
                ['java', '-cp', str(build), 'ExternalProbe', nonce], capture_output=True, timeout=15)
        except subprocess.TimeoutExpired:
            return {'passed': False, 'details': 'external evaluator compile or execution timeout', 'changed_files': changed}
        expected_output = (nonce + ':' + str(expected_count)).encode()
        output = compiled.stdout + compiled.stderr
        if executed is not None:
            output += executed.stdout.replace(nonce.encode(), b'<completion>') + executed.stderr
        output = _normalized_diagnostics(output, temporary)
        return {
            'passed': executed is not None and executed.returncode == 0 and executed.stdout == expected_output,
            'details': 'external javac/java and independent behavior checks', 'changed_files': changed,
            'compile_exit': compiled.returncode, 'test_exit': None if executed is None else executed.returncode,
            'expected_assertions': expected_count, 'scope_valid': scope_valid,
            'scope_exit': None if scope is None else scope.returncode,
            'scope_output_sha256': None if scope is None else hashlib.sha256(
                _normalized_diagnostics(scope.stdout + scope.stderr, temporary)).hexdigest(),
            'test_output_sha256': hashlib.sha256(output).hexdigest(),
            'diff_sha256': hashlib.sha256(after[target]).hexdigest(),
        }
