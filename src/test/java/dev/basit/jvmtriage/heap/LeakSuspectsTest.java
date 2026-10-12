package dev.basit.jvmtriage.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.basit.jvmtriage.hprof.BasicType;
import dev.basit.jvmtriage.hprof.HprofBuilder;
import dev.basit.jvmtriage.hprof.HprofParser;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LeakSuspectsTest {

    private static final long CLASS_OBJECT = 1L;
    private static final long CLASS_HOLDER = 2L;

    /**
     * A dump modelled on the canonical leak: a class holds a static reference to an
     * object which in turn holds a large {@code byte[]}.
     */
    private static Snapshot leakDump() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.loadClass(CLASS_HOLDER, "com/example/Cache");
        b.classDumpWithStatic(CLASS_HOLDER, 0L, 0, "INSTANCE", BasicType.OBJECT, 100L);

        b.loadClass(3L, "com/example/Node");
        b.classDump(3L, 0L, 8, "payload", BasicType.OBJECT);
        b.instance(100L, 3L, new BasicType[] {BasicType.OBJECT}, new long[] {101L});
        b.primitiveArray(101L, BasicType.BYTE, 4096);

        // Unrelated small object hanging off its own class, for contrast.
        b.instance(200L, CLASS_OBJECT, new BasicType[0], new long[0]);
        b.rootStickyClass(200L);

        return HprofParser.parse(b.build());
    }

    @Test
    @DisplayName("ranks by retained size, so the small holder outranks nothing it holds")
    void ranksByRetainedSize() {
        Snapshot s = leakDump();
        DominatorTree d = DominatorTree.build(s);
        List<LeakSuspects.Suspect> top = LeakSuspects.topByRetainedSize(s, d, 5);

        assertFalse(top.isEmpty());
        LeakSuspects.Suspect first = top.get(0);
        assertTrue(first.retainedSize() >= 4096,
                "the top suspect must retain at least the 4 KiB array");
        // Ranking is by retained, never shallow: the winner is smaller than what it holds.
        assertTrue(first.shallowSize() < first.retainedSize());
    }

    @Test
    @DisplayName("percent-of-heap is relative to total shallow size")
    void reportsPercentOfHeap() {
        Snapshot s = leakDump();
        DominatorTree d = DominatorTree.build(s);
        LeakSuspects.Suspect first = LeakSuspects.topByRetainedSize(s, d, 5).get(0);

        double expected = (first.retainedSize() * 100.0) / s.totalShallowSize();
        assertEquals(expected, first.percentOfHeap(), 0.001);
    }

    @Test
    @DisplayName("limit caps the number of suspects returned")
    void respectsLimit() {
        Snapshot s = leakDump();
        DominatorTree d = DominatorTree.build(s);

        assertEquals(2, LeakSuspects.topByRetainedSize(s, d, 2).size());
        assertTrue(LeakSuspects.topByRetainedSize(s, d, 1000).size() < 1000);
    }

    @Test
    @DisplayName("unreachable objects never appear as suspects")
    void excludesUnreachable() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.primitiveArray(900L, BasicType.BYTE, 8192); // never referenced

        Snapshot s = HprofParser.parse(b.build());
        DominatorTree d = DominatorTree.build(s);

        assertTrue(LeakSuspects.topByRetainedSize(s, d, 10).stream()
                        .noneMatch(x -> x.objectId() == 900L),
                "an unreferenced array retains nothing and is not a suspect");
    }

    @Test
    @DisplayName("histogram aggregates instances and bytes per class, largest first")
    void histogramAggregates() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.primitiveArray(300L, BasicType.BYTE, 1000);
        b.primitiveArray(301L, BasicType.BYTE, 2000);
        b.primitiveArray(302L, BasicType.INT, 10);

        Snapshot s = HprofParser.parse(b.build());
        List<LeakSuspects.ClassTotal> hist = LeakSuspects.histogram(s, 10);

        LeakSuspects.ClassTotal bytes = hist.stream()
                .filter(c -> c.className().equals("byte[]")).findFirst().orElseThrow();
        assertEquals(2, bytes.instanceCount());
        assertEquals(3000, bytes.totalShallowSize());
        assertEquals("byte[]", hist.get(0).className(), "largest class comes first");
    }

    @Test
    @DisplayName("pathToRoot walks the dominator chain upward and terminates")
    void pathToRootTerminates() {
        Snapshot s = leakDump();
        DominatorTree d = DominatorTree.build(s);

        List<Long> path = LeakSuspects.pathToRoot(d, 101L);

        assertEquals(Long.valueOf(101L), path.get(0), "the path starts at the object itself");
        assertTrue(path.size() > 1, "the array is held by something");
        assertTrue(path.size() < 100, "the walk must terminate, not spin");
    }

    @Test
    @DisplayName("an empty heap produces empty reports rather than throwing")
    void handlesEmptyHeap() {
        Snapshot empty = new Snapshot(java.util.Map.of(), java.util.Map.of(),
                java.util.Set.of(), 8);
        DominatorTree d = DominatorTree.build(empty);

        assertTrue(LeakSuspects.topByRetainedSize(empty, d, 10).isEmpty());
        assertTrue(LeakSuspects.histogram(empty, 10).isEmpty());
        assertEquals(0L, empty.totalShallowSize());
    }

    /**
     * Several holders that retain exactly the same number of bytes, with object ids
     * chosen so that the order a {@code HashMap} yields them is not ascending id
     * order. Any ranking that relies on map iteration order will disagree with the
     * ranking a reader would call correct.
     */
    private static Snapshot tiedDump() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.loadClass(10L, "com/example/Holder");
        b.classDump(10L, 0L, 8, "payload", BasicType.OBJECT);

        long[] holderIds = {20L, 5L, 36L, 17L};
        long arrayId = 1000L;
        for (long holderId : holderIds) {
            b.instance(holderId, 10L, new BasicType[] {BasicType.OBJECT}, new long[] {arrayId});
            b.primitiveArray(arrayId, BasicType.BYTE, 1024);
            b.rootJavaFrame(holderId);
            arrayId++;
        }
        return HprofParser.parse(b.build());
    }

    @Test
    @DisplayName("suspects tied on retained size are ordered by object id, not by map iteration")
    void tiedRetainedSizesAreOrderedByObjectId() {
        Snapshot s = tiedDump();
        DominatorTree d = DominatorTree.build(s);
        List<LeakSuspects.Suspect> top = LeakSuspects.topByRetainedSize(s, d, 20);

        // Every run of a leak hunt must print the same report for the same dump.
        // Without a tie-break, equal retained sizes fall through to the order
        // Snapshot's HashMap happens to yield, which is an implementation detail
        // of the map and the JDK rather than anything about the heap.
        long previousRetained = Long.MAX_VALUE;
        long previousId = Long.MIN_VALUE;
        for (LeakSuspects.Suspect suspect : top) {
            if (suspect.retainedSize() == previousRetained) {
                assertTrue(suspect.objectId() > previousId,
                        "tied suspects must ascend by object id: saw " + previousId
                                + " then " + suspect.objectId()
                                + " at retained size " + suspect.retainedSize());
            }
            previousRetained = suspect.retainedSize();
            previousId = suspect.objectId();
        }
    }

    @Test
    @DisplayName("the same dump produces a byte-identical ranking every time")
    void rankingIsRepeatable() {
        Snapshot s = tiedDump();
        DominatorTree d = DominatorTree.build(s);
        List<LeakSuspects.Suspect> first = LeakSuspects.topByRetainedSize(s, d, 20);
        for (int run = 0; run < 5; run++) {
            assertEquals(first, LeakSuspects.topByRetainedSize(s, d, 20),
                    "ranking changed between runs on an unchanged snapshot");
        }
    }

    @Test
    @DisplayName("histogram rows tied on total bytes are ordered by class name")
    void tiedHistogramRowsAreOrderedByClassName() {
        Snapshot s = tiedDump();
        List<LeakSuspects.ClassTotal> rows = LeakSuspects.histogram(s, 20);

        long previousBytes = Long.MAX_VALUE;
        String previousName = null;
        for (LeakSuspects.ClassTotal row : rows) {
            if (row.totalShallowSize() == previousBytes && previousName != null) {
                assertTrue(row.className().compareTo(previousName) > 0,
                        "tied histogram rows must ascend by class name: saw "
                                + previousName + " then " + row.className());
            }
            previousBytes = row.totalShallowSize();
            previousName = row.className();
        }
    }

}
