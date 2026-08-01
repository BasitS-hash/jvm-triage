package dev.basit.jvmtriage.hprof;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.basit.jvmtriage.heap.ClassInfo;
import dev.basit.jvmtriage.heap.JavaObject;
import dev.basit.jvmtriage.heap.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HprofParserTest {

    private static final long CLASS_OBJECT = 1L;
    private static final long CLASS_NODE = 2L;

    @Test
    @DisplayName("rejects a file that is not an HPROF dump")
    void rejectsBadMagic() {
        HprofBuilder b = new HprofBuilder();
        byte[] dump = b.build("NOT A HEAP DUMP");
        HprofFormatException e =
                assertThrows(HprofFormatException.class, () -> HprofParser.parse(dump));
        assertTrue(e.getMessage().contains("not an HPROF dump"), e.getMessage());
    }

    @Test
    @DisplayName("reports truncation rather than mis-parsing")
    void rejectsTruncatedDump() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_NODE, "Node");
        byte[] full = b.build();
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 5);
        assertThrows(HprofFormatException.class, () -> HprofParser.parse(cut));
    }

    @Test
    @DisplayName("an unknown heap sub-record fails loudly instead of silently desyncing")
    void rejectsUnknownSubRecord() {
        HprofBuilder b = new HprofBuilder();
        b.rawHeapByte(0x7E); // not a defined sub-record tag
        HprofFormatException e =
                assertThrows(HprofFormatException.class, () -> HprofParser.parse(b.build()));
        assertTrue(e.getMessage().contains("unknown heap sub-record"), e.getMessage());
    }

    @Test
    @DisplayName("class names are read and converted from JVM internal form")
    void readsClassNames() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_NODE, "com/example/Node");
        b.classDump(CLASS_NODE, 0L, 16);

        Snapshot s = HprofParser.parse(b.build());
        ClassInfo ci = s.classes().get(CLASS_NODE);
        assertEquals("com.example.Node", ci.name());
    }

    @Test
    @DisplayName("instance reference fields become graph edges, primitives do not")
    void decodesInstanceReferences() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_NODE, "Node");
        b.classDump(CLASS_NODE, 0L, 16, "next", BasicType.OBJECT, "count", BasicType.INT);

        long a = 100L;
        long target = 101L;
        b.instance(a, CLASS_NODE,
                new BasicType[] {BasicType.OBJECT, BasicType.INT},
                new long[] {target, 42});
        b.instance(target, CLASS_NODE,
                new BasicType[] {BasicType.OBJECT, BasicType.INT},
                new long[] {0L, 7});

        Snapshot s = HprofParser.parse(b.build());

        assertEquals(1, s.objects().get(a).references().length);
        assertEquals(target, s.objects().get(a).references()[0]);
        // Null reference is dropped, so the terminal node has no outgoing edges.
        assertEquals(0, s.objects().get(target).references().length);
    }

    @Test
    @DisplayName("inherited fields are decoded, most-derived class first")
    void decodesInheritedFields() {
        HprofBuilder b = new HprofBuilder();
        long base = 10L;
        long derived = 11L;
        b.loadClass(base, "Base");
        b.loadClass(derived, "Derived");
        // Base declares one reference; Derived declares one reference of its own.
        b.classDump(base, 0L, 8, "baseRef", BasicType.OBJECT);
        b.classDump(derived, base, 16, "derivedRef", BasicType.OBJECT);

        long obj = 200L;
        long p = 201L;
        long q = 202L;
        // Field block order: Derived's fields, then Base's.
        b.instance(obj, derived,
                new BasicType[] {BasicType.OBJECT, BasicType.OBJECT},
                new long[] {p, q});
        b.instance(p, base, new BasicType[] {BasicType.OBJECT}, new long[] {0L});
        b.instance(q, base, new BasicType[] {BasicType.OBJECT}, new long[] {0L});

        Snapshot s = HprofParser.parse(b.build());
        long[] refs = s.objects().get(obj).references();

        assertEquals(2, refs.length, "should follow the superclass chain");
        assertEquals(p, refs[0], "derived class fields come first in the block");
        assertEquals(q, refs[1]);
    }

    @Test
    @DisplayName("object arrays expose their elements as references")
    void decodesObjectArrays() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);

        long arr = 300L;
        long e1 = 301L;
        long e2 = 302L;
        // A null slot in the middle must not become an edge.
        b.objectArray(arr, CLASS_OBJECT, e1, 0L, e2);
        b.instance(e1, CLASS_OBJECT, new BasicType[0], new long[0]);
        b.instance(e2, CLASS_OBJECT, new BasicType[0], new long[0]);

        Snapshot s = HprofParser.parse(b.build());
        JavaObject a = s.objects().get(arr);

        assertEquals(2, a.references().length);
        assertEquals(3, a.arrayLength(), "declared length keeps the null slot");
        assertEquals(JavaObject.Kind.OBJECT_ARRAY, a.kind());
    }

    @Test
    @DisplayName("primitive array size is length x element width")
    void sizesPrimitiveArrays() {
        HprofBuilder b = new HprofBuilder();
        b.primitiveArray(400L, BasicType.BYTE, 1024);
        b.primitiveArray(401L, BasicType.LONG, 10);

        Snapshot s = HprofParser.parse(b.build());

        assertEquals(1024L, s.objects().get(400L).shallowSize());
        assertEquals(80L, s.objects().get(401L).shallowSize());
        assertEquals(0, s.objects().get(400L).references().length);
    }

    @Test
    @DisplayName("a 4-byte identifier dump parses as readily as an 8-byte one")
    void supportsFourByteIdentifiers() {
        HprofBuilder b = new HprofBuilder(4);
        b.loadClass(CLASS_NODE, "Node");
        b.classDump(CLASS_NODE, 0L, 8, "next", BasicType.OBJECT);
        b.instance(100L, CLASS_NODE, new BasicType[] {BasicType.OBJECT}, new long[] {101L});
        b.instance(101L, CLASS_NODE, new BasicType[] {BasicType.OBJECT}, new long[] {0L});

        Snapshot s = HprofParser.parse(b.build());

        assertEquals(4, s.identifierSize());
        assertEquals(101L, s.objects().get(100L).references()[0]);
    }

    @Test
    @DisplayName("GC roots naming absent objects are dropped")
    void dropsDanglingRoots() {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.instance(500L, CLASS_OBJECT, new BasicType[0], new long[0]);
        b.rootStickyClass(500L);
        b.rootJavaFrame(999L); // never dumped

        Snapshot s = HprofParser.parse(b.build());

        assertTrue(s.gcRoots().contains(500L));
        assertFalse(s.gcRoots().contains(999L), "dangling root must not survive");
        // Class objects are also roots (they anchor static fields), so the total is
        // the dumped instance plus every class — not a bare count of explicit roots.
        assertTrue(s.gcRoots().contains(CLASS_OBJECT), "classes anchor their statics");
    }

    @Test
    @DisplayName("objects held only by a static field stay reachable")
    void staticFieldsAreRoots() {
        // The canonical Java leak. Before class objects were materialised into the
        // graph, everything behind a static collection looked like garbage — exactly
        // inverting the answer a leak hunt needs.
        HprofBuilder b = new HprofBuilder();
        long holder = 20L;
        long payload = 600L;

        b.loadClass(CLASS_OBJECT, "java/lang/Object");
        b.classDump(CLASS_OBJECT, 0L, 0);
        b.loadClass(holder, "Holder");
        b.classDumpWithStatic(holder, 0L, 0, "CACHE", BasicType.OBJECT, payload);
        b.instance(payload, CLASS_OBJECT, new BasicType[0], new long[0]);

        Snapshot s = HprofParser.parse(b.build());

        assertTrue(s.gcRoots().contains(holder), "the class itself is a root");
        long[] refs = s.objects().get(holder).references();
        assertEquals(1, refs.length, "the static reference field is an edge");
        assertEquals(payload, refs[0]);
    }

    @Test
    @DisplayName("primitive arrays are named rather than reported as unknown")
    void namesPrimitiveArrays() {
        HprofBuilder b = new HprofBuilder();
        b.primitiveArray(700L, BasicType.BYTE, 16);
        b.primitiveArray(701L, BasicType.INT, 4);

        Snapshot s = HprofParser.parse(b.build());

        assertEquals("byte[]", s.classNameOf(s.objects().get(700L)));
        assertEquals("int[]", s.classNameOf(s.objects().get(701L)));
    }
}
