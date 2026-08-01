package dev.basit.jvmtriage.threads;

import java.util.List;

/**
 * One thread as described by a {@code jstack}-format dump.
 *
 * <p>{@link #locksHeld()} and {@link #lockWaitedOn()} are the two facts a deadlock
 * search needs. {@code lockWaitedOn} is the monitor the thread is blocked trying to
 * <em>acquire</em>; a monitor it is sitting in {@code Object.wait()} on is not the
 * same thing and does not belong in a wait-for cycle, because a waiting thread has
 * released that monitor.
 */
public record ThreadInfo(
        String name,
        String state,
        long tid,
        List<String> stackFrames,
        List<String> locksHeld,
        String lockWaitedOn,
        String lockWaitedOnClass) {

    public ThreadInfo {
        stackFrames = List.copyOf(stackFrames);
        locksHeld = List.copyOf(locksHeld);
    }

    public boolean isBlocked() {
        return "BLOCKED".equals(state);
    }

    /** The top application frame, used to label a thread in a report. */
    public String topFrame() {
        return stackFrames.isEmpty() ? "<no stack>" : stackFrames.get(0);
    }
}
