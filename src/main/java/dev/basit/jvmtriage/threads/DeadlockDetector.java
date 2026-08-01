package dev.basit.jvmtriage.threads;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds deadlocks by locating cycles in the wait-for graph.
 *
 * <p>The graph has one node per thread and an edge T → U when T is blocked
 * acquiring a monitor that U currently holds. Any cycle in that graph is a
 * deadlock: every thread in it is waiting on the next, and none can proceed.
 *
 * <p>This finds deadlocks the JVM's own "Found one Java-level deadlock" banner
 * misses — that banner only covers monitors and {@code java.util.concurrent} locks
 * the VM can introspect, and it is absent entirely from dumps taken with
 * {@code kill -3} on some VMs, or from a dump a customer has trimmed before
 * attaching it to a ticket.
 *
 * <p>Cycles are found with an iterative three-colour DFS. Recursion is avoided for
 * the same reason as in the heap graph: thread counts in the thousands are normal
 * in an application server dump.
 */
public final class DeadlockDetector {

    private DeadlockDetector() {}

    /** A cycle of threads, each waiting on a lock held by the next. */
    public record Deadlock(List<ThreadInfo> cycle, List<String> locks) {

        public Deadlock {
            cycle = List.copyOf(cycle);
            locks = List.copyOf(locks);
        }

        public String describe() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < cycle.size(); i++) {
                ThreadInfo t = cycle.get(i);
                ThreadInfo next = cycle.get((i + 1) % cycle.size());
                sb.append("  \"").append(t.name()).append("\" waits for ")
                        .append(t.lockWaitedOn());
                if (t.lockWaitedOnClass() != null) {
                    sb.append(" (a ").append(t.lockWaitedOnClass()).append(")");
                }
                sb.append(" held by \"").append(next.name()).append("\"")
                        .append(System.lineSeparator());
            }
            return sb.toString();
        }
    }

    public static List<Deadlock> findDeadlocks(List<ThreadInfo> threads) {
        Map<String, ThreadInfo> holderOfLock = new HashMap<>();
        for (ThreadInfo t : threads) {
            for (String lock : t.locksHeld()) {
                holderOfLock.put(lock, t);
            }
        }

        // waitsFor: thread -> the thread holding the monitor it is blocked on.
        Map<ThreadInfo, ThreadInfo> waitsFor = new LinkedHashMap<>();
        for (ThreadInfo t : threads) {
            String wanted = t.lockWaitedOn();
            if (wanted == null) {
                continue;
            }
            ThreadInfo holder = holderOfLock.get(wanted);
            // Self-held is not a deadlock: monitors are reentrant.
            if (holder != null && holder != t) {
                waitsFor.put(t, holder);
            }
        }

        return collectCycles(threads, waitsFor);
    }

    private static List<Deadlock> collectCycles(
            List<ThreadInfo> threads, Map<ThreadInfo, ThreadInfo> waitsFor) {

        List<Deadlock> found = new ArrayList<>();
        Set<ThreadInfo> settled = new LinkedHashSet<>();

        for (ThreadInfo start : threads) {
            if (settled.contains(start) || !waitsFor.containsKey(start)) {
                continue;
            }

            // Each node has at most one outgoing edge, so following the chain is
            // enough — no branching search is required.
            Map<ThreadInfo, Integer> positionInPath = new LinkedHashMap<>();
            List<ThreadInfo> path = new ArrayList<>();
            ThreadInfo cursor = start;

            while (cursor != null && !settled.contains(cursor)) {
                Integer seenAt = positionInPath.get(cursor);
                if (seenAt != null) {
                    List<ThreadInfo> cycle = new ArrayList<>(path.subList(seenAt, path.size()));
                    List<String> locks = new ArrayList<>(cycle.size());
                    for (ThreadInfo t : cycle) {
                        locks.add(t.lockWaitedOn());
                    }
                    found.add(new Deadlock(cycle, locks));
                    break;
                }
                positionInPath.put(cursor, path.size());
                path.add(cursor);
                cursor = waitsFor.get(cursor);
            }

            // Everything walked on this pass is resolved; re-walking it from another
            // start would only rediscover the same cycle.
            settled.addAll(path);
        }
        return found;
    }
}
