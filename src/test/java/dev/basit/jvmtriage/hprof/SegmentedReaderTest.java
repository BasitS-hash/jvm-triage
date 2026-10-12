package dev.basit.jvmtriage.hprof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.basit.jvmtriage.heap.JavaObject;
import dev.basit.jvmtriage.heap.Snapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the segmented mapping with test-only segment sizes.
 *
 * <p>A segmented reader tested only on files smaller than one segment is untested:
 * every bug lives at the seam. These tests use segments of 4 to 64 bytes so a dump
 * of a few hundred bytes spans dozens of them, which puts ids, lengths, UTF-8
 * strings and array payloads across boundaries without needing a 2 GiB fixture.
 */
class SegmentedReaderTest {

    private static final long CLASS_OBJECT = 1L;
    private static final long CLASS_BASE = 2L;
    private static final long CLASS_DERIVED = 3L;
    private static final long CLASS_HOLDER = 4L;

    @TempDir
    Path tmp;

    /**
     * Segment sizes in bits. 2 is pathological — a 4-byte segment is smaller than a
     * single 8-byte identifier, so every id read spans two whole segments; 3 makes
     * an id exactly fill one.
     */
    private static final int[] SEGMENT_BITS = {2, 3, 4, 6, 10, 20};

    @Test
    @DisplayName("a dump parses identically at every segment size, including a 4-byte one")
    void parsesIdenticallyAtEverySegmentSize() throws IOException {
        byte[] dump = representativeDump(0);
        Path file = Files.write(tmp.resolve("heap.hprof"), dump);

        String expected = describe(HprofParser.parse(dump));
        for (int bits : SEGMENT_BITS) {
            assertEquals(expected, describe(HprofParser.parse(file, bits)),
                    "parse differed at " + (1L << bits) + "-byte segments");
        }
    }

    @Test
    @DisplayName("every byte alignment of the heap segment against the boundary parses the same")
    void parsesIdenticallyAtEveryAlignment() throws IOException {
        int bits = 4; // 16-byte segments
        String expected = describe(HprofParser.parse(representativeDump(0)));

        // Sliding the whole heap section by 0..15 bytes walks every record field
        // through every position relative to a boundary.
        for (int shift = 0; shift < (1 << bits); shift++) {
            byte[] dump = representativeDump(shift);
            Path file = Files.write(tmp.resolve("shift-" + shift + ".hprof"), dump);
            assertEquals(expected, describe(HprofParser.parse(file, bits)),
                    "parse differed with the heap section shifted by " + shift + " bytes");
        }
    }

    @Test
    @DisplayName("an object id placed deliberately across a boundary is not mis-read")
    void readsIdPlacedAcrossABoundary() throws IOException {
        int bits = 6;
        long segmentSize = 1L << bits;

        // Probe once to learn where the INSTANCE_DUMP lands, then rebuild with the
        // padding that drops its 8-byte object id across a seam, four bytes a side.
        long[] where = new long[1];
        straddleDump(MIN_PADDING, where);
        long idOffset = where[0] + 1; // sub-record tag, then the id
        int pad = MIN_PADDING
                + (int) (((segmentSize - 4) - (idOffset % segmentSize) + segmentSize) % segmentSize);

        byte[] dump = straddleDump(pad, where);
        idOffset = where[0] + 1;
        assertEquals(segmentSize - 4, idOffset % segmentSize, "id should start 4 bytes from the seam");
        assertTrue(idOffset / segmentSize != (idOffset + 7) / segmentSize,
                "the test is pointless unless the id really spans two segments");

        Path file = Files.write(tmp.resolve("straddle.hprof"), dump);
        Snapshot s = HprofParser.parse(file, bits);

        assertEquals(describe(HprofParser.parse(dump)), describe(s));
        assertEquals(101L, s.objects().get(100L).references()[0],
                "the straddling id must read as itself, not as two half-ids");
    }

    @Test
    @DisplayName("a dump larger than one segment is read rather than rejected")
    void acceptsADumpSpanningManySegments() throws IOException {
        int bits = 6;
        byte[] dump = representativeDump(0);
        Path file = Files.write(tmp.resolve("many.hprof"), dump);

        assertTrue(dump.length > (1L << bits) * 4,
                "fixture should span several segments, was " + dump.length + " bytes");
        assertEquals(describe(HprofParser.parse(dump)), describe(HprofParser.parse(file, bits)));
    }

    // ---- fixtures -----------------------------------------------------------

    private static final int MIN_PADDING = 9;

    /**
     * A dump using every record kind the parser decodes, shifted by {@code shift}
     * bytes so the same content lands at different offsets.
     */
    private static byte[] representativeDump(int shift) {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.loadClass(CLASS_BASE, "com/example/Base");
        b.loadClass(CLASS_DERIVED, "com/example/Derived");
        b.loadClass(CLASS_HOLDER, "com/example/Holder");

        b.classDump(CLASS_OBJECT, 0L, 0);
        b.classDump(CLASS_BASE, 0L, 16, "baseRef", BasicType.OBJECT, "tag", BasicType.LONG);
        b.classDump(CLASS_DERIVED, CLASS_BASE, 32, "derivedRef", BasicType.OBJECT,
                "flag", BasicType.BOOLEAN, "code", BasicType.CHAR);
        b.classDumpWithStatic(CLASS_HOLDER, 0L, 0, "CACHE", BasicType.OBJECT, 300L);

        b.instance(100L, CLASS_DERIVED,
                new BasicType[] {BasicType.OBJECT, BasicType.BOOLEAN, BasicType.CHAR,
                                 BasicType.OBJECT, BasicType.LONG},
                new long[] {200L, 1, 'x', 201L, 0x0102030405060708L});
        b.instance(200L, CLASS_BASE,
                new BasicType[] {BasicType.OBJECT, BasicType.LONG}, new long[] {0L, 7});
        b.instance(201L, CLASS_BASE,
                new BasicType[] {BasicType.OBJECT, BasicType.LONG}, new long[] {202L, 9});
        b.instance(202L, CLASS_OBJECT, new BasicType[0], new long[0]);
        b.instance(300L, CLASS_OBJECT, new BasicType[0], new long[0]);

        b.objectArray(400L, CLASS_OBJECT, 200L, 0L, 201L, 202L);
        // Payloads wider than a small segment, so the skip over an ARRAY body has
        // to cross seams rather than jump within one buffer.
        b.primitiveArray(500L, BasicType.BYTE, 257);
        b.primitiveArray(501L, BasicType.LONG, 33);
        b.rootJavaFrame(100L);
        b.rootStickyClass(400L);

        if (shift > 0) {
            b.padding(MIN_PADDING + shift - 1);
        }
        return b.build();
    }

    /**
     * A small dump whose first INSTANCE_DUMP is preceded by {@code pad} bytes of
     * ignorable record. {@code offsetOut[0]} receives the absolute file offset of
     * that sub-record's first byte.
     */
    private static byte[] straddleDump(int pad, long[] offsetOut) {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_BASE, "com/example/Base");
        b.classDump(CLASS_BASE, 0L, 8, "next", BasicType.OBJECT);
        b.padding(pad);

        int mark = b.heapMark();
        b.instance(100L, CLASS_BASE, new BasicType[] {BasicType.OBJECT}, new long[] {101L});
        b.instance(101L, CLASS_BASE, new BasicType[] {BasicType.OBJECT}, new long[] {0L});

        byte[] bytes = b.build();
        offsetOut[0] = b.heapPayloadOffset() + mark;
        return bytes;
    }

    /**
     * Renders everything the parser produced as text, so a mismatch at any segment
     * size shows up as a readable diff rather than a bare boolean.
     */
    private static String describe(Snapshot s) {
        StringBuilder sb = new StringBuilder();
        sb.append("idSize=").append(s.identifierSize()).append('\n');
        sb.append("shallow=").append(s.totalShallowSize()).append('\n');

        List<String> classNames = new ArrayList<>();
        s.classes().values().forEach(c -> classNames.add(c.name()));
        classNames.sort(null);
        sb.append("classes=").append(classNames).append('\n');

        for (var e : new TreeMap<>(s.objects()).entrySet()) {
            JavaObject o = e.getValue();
            sb.append(String.format("obj %d %s %s shallow=%d len=%d refs=%s%n",
                    o.id(), s.classNameOf(o), o.kind(), o.shallowSize(), o.arrayLength(),
                    java.util.Arrays.toString(o.references())));
        }
        List<Long> roots = new ArrayList<>(s.gcRoots());
        roots.sort(null);
        sb.append("roots=").append(roots).append('\n');
        return sb.toString();
    }
}
