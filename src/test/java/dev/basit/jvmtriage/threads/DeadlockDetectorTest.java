package dev.basit.jvmtriage.threads;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeadlockDetectorTest {

    private static final String TWO_THREAD_DEADLOCK = """
            "Thread-A" #10 prio=5 tid=0x00007f0000000001 nid=0x1 waiting for monitor entry
               java.lang.Thread.State: BLOCKED (on object monitor)
            	at com.example.Service.transfer(Service.java:42)
            	- waiting to lock <0x00000000ffff0002> (a com.example.Account)
            	- locked <0x00000000ffff0001> (a com.example.Account)
            	at com.example.Worker.run(Worker.java:11)

            "Thread-B" #11 prio=5 tid=0x00007f0000000002 nid=0x2 waiting for monitor entry
               java.lang.Thread.State: BLOCKED (on object monitor)
            	at com.example.Service.transfer(Service.java:42)
            	- waiting to lock <0x00000000ffff0001> (a com.example.Account)
            	- locked <0x00000000ffff0002> (a com.example.Account)
            	at com.example.Worker.run(Worker.java:11)
            """;

    private static final String HEALTHY_DUMP = """
            "main" #1 prio=5 tid=0x00007f0000000001 nid=0x1 runnable
               java.lang.Thread.State: RUNNABLE
            	at com.example.App.main(App.java:7)

            "worker" #2 prio=5 tid=0x00007f0000000002 nid=0x2 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            	at jdk.internal.misc.Unsafe.park(Native Method)
            	- parking to wait for <0x00000000ffff0009> (a java.util.concurrent.SynchronousQueue)
            """;


    // A real ReentrantLock deadlock, in the shape jcmd Thread.print actually emits.
    // Note what is absent: no "locked <0x...>" line anywhere. The only record of who
    // owns what is the trailing "Locked ownable synchronizers" block.
    private static final String REENTRANT_LOCK_DEADLOCK = """
            "Thread-A" #10 prio=5 tid=0x00007f0000000001 nid=0x1 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            	at jdk.internal.misc.Unsafe.park(Native Method)
            	- parking to wait for <0x00000000ffff0002> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
            	at com.example.Service.transfer(Service.java:42)

               Locked ownable synchronizers:
            	- <0x00000000ffff0001> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)

            "Thread-B" #11 prio=5 tid=0x00007f0000000002 nid=0x2 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            	at jdk.internal.misc.Unsafe.park(Native Method)
            	- parking to wait for <0x00000000ffff0001> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
            	at com.example.Service.transfer(Service.java:42)

               Locked ownable synchronizers:
            	- <0x00000000ffff0002> (a java.util.concurrent.locks.ReentrantLock$NonfairSync)
            """;

    // A thread that parks on a queue and owns nothing. "- None" must not be read as
    // a held lock, or every idle pool thread becomes a spurious owner.
    private static final String OWNS_NOTHING = """
            "pool-1-thread-1" #12 prio=5 tid=0x00007f0000000003 nid=0x3 waiting on condition
               java.lang.Thread.State: WAITING (parking)
            	at jdk.internal.misc.Unsafe.park(Native Method)
            	- parking to wait for <0x00000000ffff0009> (a java.util.concurrent.SynchronousQueue)

               Locked ownable synchronizers:
            	- None
            """;

    @Test
    @DisplayName("parses thread name, state, held locks and awaited lock")
    void parsesThreadFacts() {
        List<ThreadInfo> threads = ThreadDumpParser.parse(TWO_THREAD_DEADLOCK);

        assertEquals(2, threads.size());
        ThreadInfo a = threads.get(0);
        assertEquals("Thread-A", a.name());
        assertEquals("BLOCKED", a.state());
        assertTrue(a.isBlocked());
        assertEquals("0x00000000ffff0002", a.lockWaitedOn());
        assertEquals("com.example.Account", a.lockWaitedOnClass());
        assertEquals(List.of("0x00000000ffff0001"), a.locksHeld());
        assertEquals("com.example.Service.transfer(Service.java:42)", a.topFrame());
    }

    @Test
    @DisplayName("detects a classic two-thread lock-ordering deadlock")
    void detectsTwoThreadDeadlock() {
        List<ThreadInfo> threads = ThreadDumpParser.parse(TWO_THREAD_DEADLOCK);
        List<DeadlockDetector.Deadlock> found = DeadlockDetector.findDeadlocks(threads);

        assertEquals(1, found.size());
        assertEquals(2, found.get(0).cycle().size());

        String described = found.get(0).describe();
        assertTrue(described.contains("Thread-A"), described);
        assertTrue(described.contains("Thread-B"), described);
    }

    @Test
    @DisplayName("a healthy dump yields no deadlocks")
    void healthyDumpIsClean() {
        List<ThreadInfo> threads = ThreadDumpParser.parse(HEALTHY_DUMP);

        assertEquals(2, threads.size());
        assertTrue(DeadlockDetector.findDeadlocks(threads).isEmpty(),
                "a parked thread waiting on an unheld queue is not a deadlock");
    }

    @Test
    @DisplayName("detects a three-thread cycle")
    void detectsThreeThreadCycle() {
        String dump = """
                "T1" #1 tid=0x1 nid=0x1 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0xAAA2> (a X)
                	- locked <0xAAA1> (a X)

                "T2" #2 tid=0x2 nid=0x2 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.b(X.java:2)
                	- waiting to lock <0xAAA3> (a X)
                	- locked <0xAAA2> (a X)

                "T3" #3 tid=0x3 nid=0x3 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.c(X.java:3)
                	- waiting to lock <0xAAA1> (a X)
                	- locked <0xAAA3> (a X)
                """;
        List<DeadlockDetector.Deadlock> found =
                DeadlockDetector.findDeadlocks(ThreadDumpParser.parse(dump));

        assertEquals(1, found.size());
        assertEquals(3, found.get(0).cycle().size());
    }

    @Test
    @DisplayName("a thread blocked on a lock nobody holds is not a deadlock")
    void blockedButNoHolder() {
        String dump = """
                "T1" #1 tid=0x1 nid=0x1 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0xDEAD> (a X)
                """;
        assertTrue(DeadlockDetector.findDeadlocks(ThreadDumpParser.parse(dump)).isEmpty());
    }

    @Test
    @DisplayName("reentrant self-acquisition is not reported as a deadlock")
    void selfHeldLockIsNotDeadlock() {
        // Monitors are reentrant, so a thread listed as both holding and awaiting
        // the same monitor is a dump artefact, not a hang.
        String dump = """
                "T1" #1 tid=0x1 nid=0x1 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0xBEEF> (a X)
                	- locked <0xBEEF> (a X)
                """;
        assertTrue(DeadlockDetector.findDeadlocks(ThreadDumpParser.parse(dump)).isEmpty());
    }

    @Test
    @DisplayName("two independent deadlocks are both reported")
    void reportsMultipleDeadlocks() {
        String dump = """
                "A1" #1 tid=0x1 nid=0x1 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0x1002> (a X)
                	- locked <0x1001> (a X)

                "A2" #2 tid=0x2 nid=0x2 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0x1001> (a X)
                	- locked <0x1002> (a X)

                "B1" #3 tid=0x3 nid=0x3 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at Y.a(Y.java:1)
                	- waiting to lock <0x2002> (a Y)
                	- locked <0x2001> (a Y)

                "B2" #4 tid=0x4 nid=0x4 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at Y.a(Y.java:1)
                	- waiting to lock <0x2001> (a Y)
                	- locked <0x2002> (a Y)
                """;
        assertEquals(2, DeadlockDetector.findDeadlocks(ThreadDumpParser.parse(dump)).size());
    }

    @Test
    @DisplayName("an empty or unrecognised dump parses to nothing rather than throwing")
    void toleratesJunk() {
        assertTrue(ThreadDumpParser.parse("").isEmpty());
        assertTrue(ThreadDumpParser.parse("not a thread dump at all").isEmpty());
    }

    @Test
    @DisplayName("finds a ReentrantLock deadlock, where ownership is only in the ownable block")
    void findsReentrantLockDeadlock() {
        List<ThreadInfo> threads = ThreadDumpParser.parse(REENTRANT_LOCK_DEADLOCK);

        assertEquals(2, threads.size());
        // Before the ownable block was parsed, both of these were empty: the waiting
        // edge existed, the owning edge did not, and no cycle could form.
        assertEquals(List.of("0x00000000ffff0001"), threads.get(0).locksHeld());
        assertEquals(List.of("0x00000000ffff0002"), threads.get(1).locksHeld());

        List<DeadlockDetector.Deadlock> found = DeadlockDetector.findDeadlocks(threads);
        assertEquals(1, found.size());
        assertEquals(2, found.get(0).cycle().size());
    }

    @Test
    @DisplayName("\"- None\" in the ownable block is not a held lock")
    void ownsNothingHoldsNoLocks() {
        List<ThreadInfo> threads = ThreadDumpParser.parse(OWNS_NOTHING);

        assertEquals(1, threads.size());
        assertTrue(threads.get(0).locksHeld().isEmpty());
        assertTrue(DeadlockDetector.findDeadlocks(threads).isEmpty());
    }
}
