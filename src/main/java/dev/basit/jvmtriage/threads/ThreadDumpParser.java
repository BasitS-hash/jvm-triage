package dev.basit.jvmtriage.threads;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code jstack} / {@code kill -3} thread dumps.
 *
 * <p>The format is human-oriented text with no version marker, so this is
 * deliberately tolerant: unrecognised lines are skipped rather than rejected, and a
 * thread is emitted as soon as its header is seen even if the body is unusual. A
 * support engineer's dump is often truncated by a log rotation or pasted out of a
 * ticket, and refusing to parse it would be worse than parsing what is there.
 */
public final class ThreadDumpParser {

    // "name" #12 daemon prio=5 os_prio=0 tid=0x00007f... nid=0x1 waiting for monitor entry
    private static final Pattern THREAD_HEADER = Pattern.compile(
            "^\"(?<name>.*)\"\\s+(?:#(?<num>\\d+)\\s+)?.*?"
                    + "(?:tid=(?<tid>0x[0-9a-fA-F]+))?.*$");

    private static final Pattern STATE_LINE = Pattern.compile(
            "^\\s*java\\.lang\\.Thread\\.State:\\s+(?<state>[A-Z_]+).*$");

    private static final Pattern WAITING_TO_LOCK = Pattern.compile(
            "^\\s*-\\s+waiting to lock\\s+<(?<addr>0x[0-9a-fA-F]+)>\\s*(?:\\(a (?<cls>[^)]+)\\))?.*$");

    private static final Pattern PARKING_TO_WAIT = Pattern.compile(
            "^\\s*-\\s+parking to wait for\\s+<(?<addr>0x[0-9a-fA-F]+)>\\s*(?:\\(a (?<cls>[^)]+)\\))?.*$");

    private static final Pattern LOCKED = Pattern.compile(
            "^\\s*-\\s+locked\\s+<(?<addr>0x[0-9a-fA-F]+)>.*$");

    // A ReentrantLock's owner is never reported by "locked <0x...>". It appears only
    // in this trailing section, so a dump where the contended locks are j.u.c. gives
    // us the waiting edge and no owning edge — no cycle, and a clean bill of health
    // for a real deadlock. Parsing it is what makes the j.u.c. case work at all.
    private static final Pattern OWNABLE_HEADER = Pattern.compile(
            "^\\s*Locked ownable synchronizers:\\s*$");

    // Entries are bare addresses with no verb: "- <0x...> (a ...Sync)". "- None" has
    // no address and so does not match.
    private static final Pattern OWNABLE_ENTRY = Pattern.compile(
            "^\\s*-\\s+<(?<addr>0x[0-9a-fA-F]+)>\\s*(?:\\(a (?<cls>[^)]+)\\))?\\s*$");

    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?<frame>\\S.*)$");

    private ThreadDumpParser() {}

    public static List<ThreadInfo> parse(String dump) {
        List<ThreadInfo> threads = new ArrayList<>();
        Builder current = null;
        boolean inOwnable = false;

        for (String line : dump.split("\\R")) {
            if (line.startsWith("\"")) {
                Matcher header = THREAD_HEADER.matcher(line);
                if (header.matches()) {
                    if (current != null) {
                        threads.add(current.build());
                    }
                    current = new Builder(header.group("name"), parseTid(header.group("tid")));
                    inOwnable = false;
                    continue;
                }
            }
            if (current == null) {
                continue;
            }

            Matcher m;
            if (OWNABLE_HEADER.matcher(line).matches()) {
                inOwnable = true;
                continue;
            }
            if (inOwnable && (m = OWNABLE_ENTRY.matcher(line)).matches()) {
                // Owning a synchronizer is the same fact as "locked" for the
                // wait-for graph: this thread holds it, so others block behind it.
                current.locksHeld.add(m.group("addr"));
                continue;
            }

            if ((m = STATE_LINE.matcher(line)).matches()) {
                inOwnable = false;
                current.state = m.group("state");
            } else if ((m = WAITING_TO_LOCK.matcher(line)).matches()) {
                current.lockWaitedOn = m.group("addr");
                current.lockWaitedOnClass = m.group("cls");
            } else if ((m = PARKING_TO_WAIT.matcher(line)).matches()) {
                // java.util.concurrent locks park rather than entering a monitor;
                // for wait-for purposes this is the same "wants it, hasn't got it".
                current.lockWaitedOn = m.group("addr");
                current.lockWaitedOnClass = m.group("cls");
            } else if ((m = LOCKED.matcher(line)).matches()) {
                current.locksHeld.add(m.group("addr"));
            } else if ((m = FRAME.matcher(line)).matches()) {
                inOwnable = false;
                current.frames.add(m.group("frame"));
            }
        }

        if (current != null) {
            threads.add(current.build());
        }
        return threads;
    }

    private static long parseTid(String hex) {
        if (hex == null) {
            return 0L;
        }
        try {
            return Long.parseUnsignedLong(hex.substring(2), 16);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static final class Builder {
        private final String name;
        private final long tid;
        private final List<String> frames = new ArrayList<>();
        private final List<String> locksHeld = new ArrayList<>();
        private String state = "UNKNOWN";
        private String lockWaitedOn;
        private String lockWaitedOnClass;

        Builder(String name, long tid) {
            this.name = name;
            this.tid = tid;
        }

        ThreadInfo build() {
            return new ThreadInfo(
                    name, state, tid, frames, locksHeld, lockWaitedOn, lockWaitedOnClass);
        }
    }
}
