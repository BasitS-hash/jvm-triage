package dev.basit.jvmtriage.hprof;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.basit.jvmtriage.heap.DominatorTree;
import dev.basit.jvmtriage.heap.LeakSuspects;
import dev.basit.jvmtriage.heap.Snapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Parses a dump larger than 2 GiB and checks the leak report is exactly right.
 *
 * <p>Lifting the 2 GiB ceiling was the whole point of the segmented reader, and until
 * this test existed nothing executed the path above that ceiling. Correctness rested on
 * every offset being a {@code long}, on seam tests at 4–64 byte segments, and on a
 * real-dump run that never came close to 2 GiB. The failure that argument does not rule
 * out is the dangerous one: a 32-bit truncation or a sign error in the offset arithmetic
 * does not have to crash. It can read a plausible identifier from the wrong place and
 * name the wrong object. A leak hunt pointed at the wrong object at 2am is worse than one
 * that refuses to start, so "it did not throw above 2 GiB" is not the claim under test.
 *
 * <p>The fixture holds three holders, each with a {@code byte[]} payload, placed below,
 * across and well above the boundary, and makes the straddling one the largest retainer.
 * Three reads land where they matter:
 *
 * <ul>
 *   <li>the top retainer's payload <em>identifier</em> starts at file offset
 *       {@code 0x7fff_fffc} — four bytes below {@code Integer.MAX_VALUE} and four above,
 *       which at the production segment size of 1 GiB is also exactly a mapping seam, so
 *       it is assembled by the hand-rolled straddling path;
 *   <li>that array's declared length and type tag are read entirely above the boundary;
 *   <li>a third holder sits past 2.75 GiB, so its field block goes through the bulk-copy
 *       path at an offset no existing test reaches.
 * </ul>
 *
 * <p>Mis-read any of them and the top retainer's payload reference dangles, its retained
 * size collapses and the ranking silently reorders — which is exactly what the assertions
 * below pin down, object id by object id.
 *
 * <p>Tagged {@code large} and excluded from {@code mvn test}; see the README for how it is
 * run and what it does <em>not</em> prove.
 */
@Tag("large")
class AboveTwoGibibytesTest {

    private static final long CLASS_LOW = 0x02L;
    private static final long CLASS_HIGH = 0x03L;
    private static final long CLASS_FAR = 0x04L;
    private static final long STRING_LOW_NAME = 0x10L;
    private static final long STRING_HIGH_NAME = 0x11L;
    private static final long STRING_FAR_NAME = 0x12L;
    private static final long STRING_FIELD_NAME = 0x13L;

    private static final long LOW_HOLDER = 0x1000L;
    private static final long LOW_PAYLOAD = 0x1001L;
    private static final long HIGH_HOLDER = 0x2000L;
    private static final long HIGH_PAYLOAD = 0x2001L;
    private static final long FAR_HOLDER = 0x3000L;
    private static final long FAR_PAYLOAD = 0x3001L;

    /** Payload sizes, distinct so each holder's rank is unambiguous. */
    private static final int LOW_PAYLOAD_BYTES = 256 << 20;
    private static final int HIGH_PAYLOAD_BYTES = 768 << 20;
    private static final int FAR_PAYLOAD_BYTES = 512 << 20;

    /** One instance field holding a reference, so a field block is one identifier wide. */
    private static final int INSTANCE_FIELD_BLOCK = 8;

    private static final long TWO_GIB = 1L << 31;

    /** Every top-level record: tag, microseconds, declared length. */
    private static final int RECORD_HEADER = 1 + 4 + 4;

    @TempDir
    static Path tmp;

    @BeforeAll
    static void requireRoomForTheFixture() throws IOException {
        // The fixture is written sparse, so it normally costs a few kibibytes. Ask for
        // room for all of it anyway: a filesystem without sparse-file support stores
        // every byte, and running someone's disk to zero is not an acceptable way to
        // find that out.
        long usable = Files.getFileStore(tmp).getUsableSpace();
        long needed = 5L << 30;
        assumeTrue(usable > needed,
                () -> "needs " + (needed >> 30) + " GiB free under " + tmp
                        + " in case sparse files are unsupported; " + (usable >> 20)
                        + " MiB usable");
    }

    @Test
    @DisplayName("a dump past 2 GiB attributes the leak to the right object across the boundary")
    void parsesPastTwoGibibytes() throws IOException {
        Path file = tmp.resolve("above-2gib.hprof");
        try {
            long writeStart = System.nanoTime();
            Fixture fx = writeFixture(file);
            long writeMillis = (System.nanoTime() - writeStart) / 1_000_000;

            // Assert the fixture is still the fixture before trusting what it proves. One
            // that quietly stopped straddling the boundary would pass forever.
            assertTrue(fx.size() > TWO_GIB,
                    "fixture must exceed 2 GiB, was " + fx.size() + " bytes");
            assertEquals(TWO_GIB - 4, fx.straddlingIdOffset(),
                    "the top retainer's payload id must start 4 bytes below the 2 GiB line");
            assertTrue(fx.straddlingIdOffset() + 8 > Integer.MAX_VALUE, "and end above it");
            long segmentSize = 1L << HprofReader.DEFAULT_SEGMENT_BITS;
            assertNotEquals(
                    fx.straddlingIdOffset() / segmentSize,
                    (fx.straddlingIdOffset() + 7) / segmentSize,
                    "at the production segment size it must also cross a mapping seam");
            assertTrue(fx.farHolderOffset() > TWO_GIB,
                    "the third holder must sit above the boundary, was at "
                            + fx.farHolderOffset());

            long heapBefore = settledHeapBytes();
            long parseStart = System.nanoTime();
            Snapshot s = HprofParser.parse(file);
            DominatorTree dominators = DominatorTree.build(s);
            long parseMillis = (System.nanoTime() - parseStart) / 1_000_000;
            long heapAfter = settledHeapBytes();

            // Numbers for the README and for CI logs. The heap figure is what the model
            // for this fixture costs — ten objects, so a few kilobytes. It is reported to
            // make the point, not to suggest anything about a real dump: the memory a
            // real one needs is measured in the README, not here.
            System.out.printf(
                    "[above-2GiB] file=%.2f GiB write=%d ms parse+dominators=%d ms "
                            + "model=%d KiB (%d objects)%n",
                    fx.size() / (double) (1L << 30), writeMillis, parseMillis,
                    Math.max(0, heapAfter - heapBefore) >> 10, s.objects().size());

            // --- the graph came back whole -----------------------------------------
            // Six heap objects, plus the class objects the parser materialises for the
            // three holder classes and the synthetic byte[].
            assertEquals(10, s.objects().size());
            assertEquals(0, dominators.unreachableObjects().size(),
                    "every object in this fixture is rooted");

            assertArrayEquals(new long[] {HIGH_PAYLOAD},
                    s.objects().get(HIGH_HOLDER).references(),
                    "the holder must still point at the payload whose id crosses the line");
            assertEquals(HIGH_HOLDER, dominators.immediateDominator(HIGH_PAYLOAD),
                    "a payload id read as anything else leaves the holder dominating nothing");
            assertEquals("byte[]", s.classNameOf(s.objects().get(HIGH_PAYLOAD)));
            assertEquals(HIGH_PAYLOAD_BYTES, s.objects().get(HIGH_PAYLOAD).shallowSize(),
                    "an array length read from above 2 GiB must be its real length");
            assertEquals(FAR_PAYLOAD_BYTES, s.objects().get(FAR_PAYLOAD).shallowSize());
            assertEquals(LOW_PAYLOAD_BYTES, s.objects().get(LOW_PAYLOAD).shallowSize());
            assertArrayEquals(new long[] {FAR_PAYLOAD},
                    s.objects().get(FAR_HOLDER).references(),
                    "a field block copied from past 2.75 GiB must decode to the same id");

            // --- the report is exactly right ---------------------------------------
            List<LeakSuspects.Suspect> suspects =
                    LeakSuspects.topByRetainedSize(s, dominators, 10);
            assertEquals(6, suspects.size(),
                    "only the three holders and their three payloads retain anything");

            assertSuspect(suspects.get(0), HIGH_HOLDER, "com.example.HighHolder",
                    HIGH_PAYLOAD_BYTES + (long) INSTANCE_FIELD_BLOCK);
            assertSuspect(suspects.get(1), HIGH_PAYLOAD, "byte[]", HIGH_PAYLOAD_BYTES);
            assertSuspect(suspects.get(2), FAR_HOLDER, "com.example.FarHolder",
                    FAR_PAYLOAD_BYTES + (long) INSTANCE_FIELD_BLOCK);
            assertSuspect(suspects.get(3), FAR_PAYLOAD, "byte[]", FAR_PAYLOAD_BYTES);
            assertSuspect(suspects.get(4), LOW_HOLDER, "com.example.LowHolder",
                    LOW_PAYLOAD_BYTES + (long) INSTANCE_FIELD_BLOCK);
            assertSuspect(suspects.get(5), LOW_PAYLOAD, "byte[]", LOW_PAYLOAD_BYTES);

            assertEquals(
                    LOW_PAYLOAD_BYTES + HIGH_PAYLOAD_BYTES + FAR_PAYLOAD_BYTES
                            + 3L * INSTANCE_FIELD_BLOCK,
                    s.totalShallowSize());
            assertEquals(50.0, suspects.get(0).percentOfHeap(), 0.1);
        } finally {
            // @TempDir would get there eventually, but a multi-gibibyte file should not
            // outlive the test that needed it — least of all when the test failed.
            Files.deleteIfExists(file);
        }
    }

    private static void assertSuspect(
            LeakSuspects.Suspect actual, long objectId, String className, long retained) {
        assertEquals(objectId, actual.objectId(),
                () -> "expected " + className + " at this rank, got " + actual);
        assertEquals(className, actual.className());
        assertEquals(retained, actual.retainedSize());
    }

    // ---- fixture ------------------------------------------------------------

    /** The fixture's size and the two offsets its assertions depend on. */
    private record Fixture(long size, long straddlingIdOffset, long farHolderOffset) {}

    /**
     * Writes a dump whose layout is, in file order:
     *
     * <pre>
     *   header, four strings, three LOAD_CLASS records
     *   HEAP_DUMP_SEGMENT  three CLASS_DUMPs, three roots, LowHolder, its byte[] header
     *   256 MiB hole       that byte[]'s payload
     *   CONTROL_SETTINGS   a record the parser skips, sized to position what follows
     *   ~1.75 GiB hole     that record's body
     *   HEAP_DUMP_SEGMENT  HighHolder, then its byte[] header whose id starts at 0x7ffffffc
     *   768 MiB hole       that byte[]'s payload
     *   HEAP_DUMP_SEGMENT  FarHolder and its byte[] header, all past 2.75 GiB
     *   512 MiB hole       that byte[]'s payload
     * </pre>
     *
     * <p>The padding record does the positioning, and its length is solved for rather
     * than hand-computed, so the boundary stays where it is meant to be however the
     * fixture's class names or field counts change later.
     */
    private static Fixture writeFixture(Path file) throws IOException {
        try (SparseHprofWriter w = new SparseHprofWriter(file)) {
            w.bytes("JAVA PROFILE 1.0.2".getBytes(StandardCharsets.US_ASCII))
                    .u1(0)
                    .u4(8)   // identifier size
                    .u8(0);  // dump timestamp, unused
            w.flush();

            string(w, STRING_LOW_NAME, "com/example/LowHolder");
            string(w, STRING_HIGH_NAME, "com/example/HighHolder");
            string(w, STRING_FAR_NAME, "com/example/FarHolder");
            string(w, STRING_FIELD_NAME, "payload");
            loadClass(w, CLASS_LOW, STRING_LOW_NAME);
            loadClass(w, CLASS_HIGH, STRING_HIGH_NAME);
            loadClass(w, CLASS_FAR, STRING_FAR_NAME);

            // Stage each segment's sub-records so its declared length is known before its
            // header goes out. Array payloads are holes, never staged bytes.
            classDump(w, CLASS_LOW);
            classDump(w, CLASS_HIGH);
            classDump(w, CLASS_FAR);
            rootJavaFrame(w, LOW_HOLDER);
            rootJavaFrame(w, HIGH_HOLDER);
            rootJavaFrame(w, FAR_HOLDER);
            instance(w, LOW_HOLDER, CLASS_LOW, LOW_PAYLOAD);
            primitiveArrayHeader(w, LOW_PAYLOAD, LOW_PAYLOAD_BYTES);
            heapSegment(w, LOW_PAYLOAD_BYTES, w.take());

            instance(w, HIGH_HOLDER, CLASS_HIGH, HIGH_PAYLOAD);
            byte[] highHolder = w.take();
            primitiveArrayHeader(w, HIGH_PAYLOAD, HIGH_PAYLOAD_BYTES);
            byte[] highArray = w.take();

            // Solve for the padding that lands the high payload's identifier across the
            // boundary: the padding record's own header, the heap segment's header, the
            // HighHolder record, then the array sub-record's tag — and the identifier
            // starts four bytes short of 2 GiB.
            long idWithinSegment = highHolder.length + 1;
            long padding =
                    TWO_GIB - 4 - idWithinSegment - 2L * RECORD_HEADER - w.position();
            if (padding < 0 || padding > 0xFFFF_FFFFL) {
                throw new IllegalStateException(
                        "cannot reach the boundary with one padding record: " + padding);
            }
            w.u1(HprofTag.CONTROL_SETTINGS).u4(0).u4(padding);
            w.flush();
            w.hole(padding);

            long highSegmentStart = w.position();
            heapSegment(w, HIGH_PAYLOAD_BYTES, highHolder, highArray);

            long farSegmentStart = w.position();
            instance(w, FAR_HOLDER, CLASS_FAR, FAR_PAYLOAD);
            primitiveArrayHeader(w, FAR_PAYLOAD, FAR_PAYLOAD_BYTES);
            heapSegment(w, FAR_PAYLOAD_BYTES, w.take());

            return new Fixture(
                    w.finish(),
                    highSegmentStart + RECORD_HEADER + idWithinSegment,
                    farSegmentStart + RECORD_HEADER);
        }
    }

    private static void string(SparseHprofWriter w, long id, String value) throws IOException {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        w.u1(HprofTag.STRING_IN_UTF8).u4(0).u4(8 + utf8.length).id(id).bytes(utf8);
        w.flush();
    }

    private static void loadClass(SparseHprofWriter w, long classId, long nameId)
            throws IOException {
        w.u1(HprofTag.LOAD_CLASS).u4(0).u4(4 + 8 + 4 + 8)
                .u4(1)          // class serial
                .id(classId)
                .u4(0)          // stack trace serial
                .id(nameId);
        w.flush();
    }

    /** Writes a heap segment holding {@code parts} followed by a hole of payload. */
    private static void heapSegment(SparseHprofWriter w, long holeBytes, byte[]... parts)
            throws IOException {
        long length = holeBytes;
        for (byte[] part : parts) {
            length += part.length;
        }
        w.u1(HprofTag.HEAP_DUMP_SEGMENT).u4(0).u4(length);
        for (byte[] part : parts) {
            w.bytes(part);
        }
        w.flush();
        w.hole(holeBytes);
    }

    /** Stages a CLASS_DUMP declaring one reference field named {@code payload}. */
    private static void classDump(SparseHprofWriter w, long classId) {
        w.u1(HprofTag.CLASS_DUMP)
                .id(classId)
                .u4(0)                      // stack trace serial
                .id(0)                      // superclass
                .id(0)                      // class loader
                .id(0)                      // signers
                .id(0)                      // protection domain
                .id(0)                      // reserved
                .id(0)                      // reserved
                .u4(INSTANCE_FIELD_BLOCK)
                .u2(0)                      // constant pool entries
                .u2(0)                      // static fields
                .u2(1)                      // instance fields
                .id(STRING_FIELD_NAME)
                .u1(BasicType.OBJECT.tag());
    }

    private static void instance(SparseHprofWriter w, long objId, long classId, long payload) {
        w.u1(HprofTag.INSTANCE_DUMP)
                .id(objId)
                .u4(0)                      // stack trace serial
                .id(classId)
                .u4(INSTANCE_FIELD_BLOCK)
                .id(payload);
    }

    private static void primitiveArrayHeader(SparseHprofWriter w, long objId, int length) {
        w.u1(HprofTag.PRIMITIVE_ARRAY_DUMP)
                .id(objId)
                .u4(0)                      // stack trace serial
                .u4(length)
                .u1(BasicType.BYTE.tag());
    }

    private static void rootJavaFrame(SparseHprofWriter w, long objId) {
        w.u1(HprofTag.ROOT_JAVA_FRAME).id(objId).u4(0).u4(0);
    }

    // ---- measurement --------------------------------------------------------

    /**
     * Live heap after asking for a collection, so the difference across the parse is the
     * object model rather than whatever the rest of the suite left lying around. Summing
     * the memory pools' peak usage was tried first and reported a figure that moved by 6x
     * depending on which tests ran before this one, which is worse than no figure.
     *
     * <p>{@code System.gc()} is a request, not a guarantee, so this is indicative. For a
     * ten-object fixture that is good enough.
     */
    private static long settledHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return runtime.totalMemory() - runtime.freeMemory();
    }
}
