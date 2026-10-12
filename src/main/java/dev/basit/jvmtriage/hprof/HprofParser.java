package dev.basit.jvmtriage.hprof;

import dev.basit.jvmtriage.heap.ClassInfo;
import dev.basit.jvmtriage.heap.JavaObject;
import dev.basit.jvmtriage.heap.Snapshot;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads an HPROF heap dump into a {@link Snapshot}.
 *
 * <p>Clean-room implementation from the format described in OpenJDK's
 * {@code heapDumper.cpp}. Parsing runs in two phases because the format does not
 * guarantee ordering: an {@code INSTANCE_DUMP} carries an opaque field block whose
 * layout is only knowable once its class <em>and every superclass</em> have been
 * read, and those {@code CLASS_DUMP} records may appear later in the file. Phase
 * one therefore records instance bodies verbatim; phase two walks each class
 * hierarchy and decodes the references.
 */
public final class HprofParser {

    private static final String MAGIC_PREFIX = "JAVA PROFILE";

    private final Map<Long, String> strings = new HashMap<>();
    private final Map<Long, Long> classNameStringIdByClassId = new HashMap<>();
    private final Map<Long, ClassInfo> classes = new HashMap<>();
    private final Map<Long, JavaObject> objects = new HashMap<>();
    private final Set<Long> gcRoots = new LinkedHashSet<>();
    private final List<RawInstance> rawInstances = new ArrayList<>();
    private final Map<BasicType, Long> syntheticArrayClasses = new HashMap<>();

    private int identifierSize = 8;
    private long nextSyntheticClassId = -1L;

    /** An instance body held until the class hierarchy is complete enough to decode it. */
    private record RawInstance(long id, long classId, byte[] fieldBlock) {}

    public static Snapshot parse(Path file) throws IOException {
        return parse(file, HprofReader.DEFAULT_SEGMENT_BITS);
    }

    /** Parses with a non-default mapping segment size. For tests only. */
    static Snapshot parse(Path file, int segmentBits) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return new HprofParser().parseBuffer(HprofReader.mapped(channel, segmentBits));
        }
    }

    public static Snapshot parse(byte[] bytes) {
        return new HprofParser().parseBuffer(new HprofReader(bytes));
    }

    private Snapshot parseBuffer(HprofReader r) {
        readHeader(r);
        readTopLevelRecords(r);
        decodeInstances();
        materializeClassObjects();
        return new Snapshot(classes, objects, resolveRoots(), identifierSize);
    }

    /**
     * Turns every class into a heap node whose outgoing edges are its static
     * reference fields, and marks it as a GC root.
     *
     * <p>Without this, anything held only by a {@code static} field looks like
     * garbage — which is precisely backwards, since a static collection is the most
     * common cause of a real Java leak. The class object is the thing keeping those
     * objects alive and it has to appear in the graph for retained sizes to mean
     * anything.
     *
     * <p>Treating every class as a root is deliberately conservative. A class is
     * strictly only a root while its loader is reachable, but proving otherwise
     * requires modelling loader liveness, and the failure mode of guessing wrong
     * here is reporting live objects as collectable — far worse in a leak hunt than
     * carrying a few unloadable classes.
     *
     * <p>Class objects are given zero shallow size so they do not inflate heap
     * totals; only what they retain is of interest.
     */
    private void materializeClassObjects() {
        // A class object is an instance of java.lang.Class. Typing it as an instance
        // of *itself* would make every class object show up in the histogram as one
        // more instance of the class it describes — so a dump with one byte[] class
        // would report an extra phantom byte[].
        long classClassId = syntheticClass("java.lang.Class");

        for (ClassInfo ci : List.copyOf(classes.values())) {
            if (ci.id() == classClassId) {
                continue;
            }
            if (objects.containsKey(ci.id())) {
                // Already present as a java.lang.Class instance; leave it alone.
                gcRoots.add(ci.id());
                continue;
            }

            List<Long> staticRefs = new ArrayList<>();
            for (ClassInfo.StaticField sf : ci.staticFields()) {
                if (sf.type().isReference() && sf.value() != 0) {
                    staticRefs.add(sf.value());
                }
            }

            long[] refs = new long[staticRefs.size()];
            for (int i = 0; i < refs.length; i++) {
                refs[i] = staticRefs.get(i);
            }
            objects.put(ci.id(), new JavaObject(
                    ci.id(), classClassId, 0L, refs, JavaObject.Kind.INSTANCE, 0));
            gcRoots.add(ci.id());
        }
    }

    /** Registers (once) a synthetic class with the given name and returns its id. */
    private long syntheticClass(String name) {
        for (Map.Entry<Long, ClassInfo> e : classes.entrySet()) {
            if (e.getValue().name().equals(name)) {
                return e.getKey();
            }
        }
        long id = nextSyntheticClassId--;
        classes.put(id, new ClassInfo(id, name, 0L, 0L, 0, List.of(), List.of()));
        return id;
    }

    private void readHeader(HprofReader r) {
        String version = r.readNullTerminatedString();
        if (!version.startsWith(MAGIC_PREFIX)) {
            throw new HprofFormatException(
                    "not an HPROF dump: header began with \"" + version + "\"");
        }
        identifierSize = (int) r.readU4();
        r.identifierSize(identifierSize);
        r.readU8(); // dump timestamp, milliseconds since epoch — not used
    }

    private void readTopLevelRecords(HprofReader r) {
        while (r.remaining() > 9) { // tag(1) + time(4) + length(4)
            int tag = r.readU1();
            r.readU4(); // microseconds since header timestamp
            long length = r.readU4();
            if (length > r.remaining()) {
                throw new HprofFormatException(
                        "record 0x" + Integer.toHexString(tag) + " declares " + length
                                + " bytes but only " + r.remaining() + " remain");
            }
            long end = r.position() + length;

            switch (tag) {
                case HprofTag.STRING_IN_UTF8 -> {
                    long id = r.readId();
                    strings.put(id, r.readUtf8(end - r.position()));
                }
                case HprofTag.LOAD_CLASS -> {
                    r.readU4(); // class serial
                    long classObjId = r.readId();
                    r.readU4(); // stack trace serial
                    classNameStringIdByClassId.put(classObjId, r.readId());
                }
                case HprofTag.HEAP_DUMP, HprofTag.HEAP_DUMP_SEGMENT -> readHeapSegment(r, end);
                default -> { /* not needed for triage */ }
            }

            // Always resynchronise on the declared length. Records we skip, and
            // trailing bytes in ones we partially read, must not shift framing.
            r.position(end);
        }
    }

    private void readHeapSegment(HprofReader r, long segmentEnd) {
        while (r.position() < segmentEnd) {
            int sub = r.readU1();
            switch (sub) {
                case HprofTag.ROOT_UNKNOWN,
                     HprofTag.ROOT_STICKY_CLASS,
                     HprofTag.ROOT_MONITOR_USED -> gcRoots.add(r.readId());

                case HprofTag.ROOT_JNI_GLOBAL -> {
                    gcRoots.add(r.readId());
                    r.readId(); // JNI global ref id
                }
                case HprofTag.ROOT_JNI_LOCAL, HprofTag.ROOT_JAVA_FRAME -> {
                    gcRoots.add(r.readId());
                    r.readU4(); // thread serial
                    r.readU4(); // frame number
                }
                case HprofTag.ROOT_NATIVE_STACK, HprofTag.ROOT_THREAD_BLOCK -> {
                    gcRoots.add(r.readId());
                    r.readU4(); // thread serial
                }
                case HprofTag.ROOT_THREAD_OBJ -> {
                    gcRoots.add(r.readId());
                    r.readU4(); // thread serial
                    r.readU4(); // stack trace serial
                }
                case HprofTag.CLASS_DUMP -> readClassDump(r);
                case HprofTag.INSTANCE_DUMP -> readInstanceDump(r);
                case HprofTag.OBJECT_ARRAY_DUMP -> readObjectArrayDump(r);
                case HprofTag.PRIMITIVE_ARRAY_DUMP -> readPrimitiveArrayDump(r);

                default -> throw new HprofFormatException(
                        "unknown heap sub-record 0x" + Integer.toHexString(sub)
                                + " at offset " + (r.position() - 1));
            }
        }
    }

    private void readClassDump(HprofReader r) {
        long classObjId = r.readId();
        r.readU4(); // stack trace serial
        long superClassId = r.readId();
        long classLoaderId = r.readId();
        r.readId(); // signers
        r.readId(); // protection domain
        r.readId(); // reserved
        r.readId(); // reserved
        int instanceSize = (int) r.readU4();

        int constantPoolCount = r.readU2();
        for (int i = 0; i < constantPoolCount; i++) {
            r.readU2(); // constant pool index
            BasicType type = BasicType.fromTag(r.readU1());
            r.readValue(type);
        }

        int staticCount = r.readU2();
        List<ClassInfo.StaticField> statics = new ArrayList<>(staticCount);
        for (int i = 0; i < staticCount; i++) {
            long nameId = r.readId();
            BasicType type = BasicType.fromTag(r.readU1());
            long value = r.readValue(type);
            statics.add(new ClassInfo.StaticField(
                    strings.getOrDefault(nameId, "<unknown>"), type, value));
        }

        int instanceFieldCount = r.readU2();
        List<ClassInfo.FieldInfo> fields = new ArrayList<>(instanceFieldCount);
        for (int i = 0; i < instanceFieldCount; i++) {
            long nameId = r.readId();
            BasicType type = BasicType.fromTag(r.readU1());
            fields.add(new ClassInfo.FieldInfo(
                    strings.getOrDefault(nameId, "<unknown>"), type));
        }

        classes.put(classObjId, new ClassInfo(
                classObjId, resolveClassName(classObjId), superClassId,
                classLoaderId, instanceSize, fields, statics));
    }

    private void readInstanceDump(HprofReader r) {
        long objId = r.readId();
        r.readU4(); // stack trace serial
        long classId = r.readId();
        int fieldBytes = (int) r.readU4();
        rawInstances.add(new RawInstance(objId, classId, r.readBytes(fieldBytes)));
    }

    private void readObjectArrayDump(HprofReader r) {
        long objId = r.readId();
        r.readU4(); // stack trace serial
        int length = (int) r.readU4();
        long classId = r.readId();

        long[] elements = new long[length];
        int kept = 0;
        for (int i = 0; i < length; i++) {
            long ref = r.readId();
            if (ref != 0) {
                elements[kept++] = ref;
            }
        }
        long[] refs = java.util.Arrays.copyOf(elements, kept);

        objects.put(objId, new JavaObject(
                objId, classId, (long) length * identifierSize, refs,
                JavaObject.Kind.OBJECT_ARRAY, length));
    }

    private void readPrimitiveArrayDump(HprofReader r) {
        long objId = r.readId();
        r.readU4(); // stack trace serial
        int length = (int) r.readU4();
        BasicType type = BasicType.fromTag(r.readU1());
        long bytes = (long) length * type.sizeInBytes(identifierSize);
        r.skip(bytes);

        // Primitive arrays have no outgoing references, but they are frequently the
        // bulk of a leak (the byte[] behind a cache, the char[] behind Strings), so
        // their size still has to land in the retained totals.
        //
        // The record carries no class id, so without a synthetic class every
        // primitive array reports as "<unknown>" — which in practice means the
        // single largest line of a leak report is unlabelled.
        objects.put(objId, new JavaObject(
                objId, syntheticArrayClass(type), bytes,
                new long[0], JavaObject.Kind.PRIMITIVE_ARRAY, length));
    }

    /**
     * Returns (creating on first use) a synthetic {@link ClassInfo} for a primitive
     * array type, so {@code byte[]} and friends are nameable in reports.
     *
     * <p>Ids are drawn from a negative counter. Real HPROF identifiers are addresses
     * and never negative, so these cannot collide with a class in the dump.
     */
    private long syntheticArrayClass(BasicType type) {
        return syntheticArrayClasses.computeIfAbsent(type, t -> {
            long id = nextSyntheticClassId--;
            classes.put(id, new ClassInfo(
                    id, t.name().toLowerCase(java.util.Locale.ROOT) + "[]",
                    0L, 0L, 0, List.of(), List.of()));
            return id;
        });
    }

    /**
     * Decodes every held-back instance body now that all classes are known.
     *
     * <p>The block holds the fields of the object's own class first, then those of
     * each superclass in turn. Only reference fields are retained; primitives are
     * stepped over by their declared width.
     */
    private void decodeInstances() {
        for (RawInstance raw : rawInstances) {
            HprofReader fieldReader = new HprofReader(raw.fieldBlock());
            fieldReader.identifierSize(identifierSize);

            List<Long> refs = new ArrayList<>();
            long walk = raw.classId();
            // Guard against a corrupt dump whose superclass chain loops.
            Set<Long> visitedClasses = new LinkedHashSet<>();

            while (walk != 0 && visitedClasses.add(walk)) {
                ClassInfo ci = classes.get(walk);
                if (ci == null) {
                    break;
                }
                for (ClassInfo.FieldInfo f : ci.instanceFields()) {
                    if (fieldReader.remaining() < f.type().sizeInBytes(identifierSize)) {
                        break;
                    }
                    long v = fieldReader.readValue(f.type());
                    if (f.type().isReference() && v != 0) {
                        refs.add(v);
                    }
                }
                walk = ci.superClassId();
            }

            long[] refArray = new long[refs.size()];
            for (int i = 0; i < refs.size(); i++) {
                refArray[i] = refs.get(i);
            }
            objects.put(raw.id(), new JavaObject(
                    raw.id(), raw.classId(), raw.fieldBlock().length, refArray,
                    JavaObject.Kind.INSTANCE, 0));
        }
        rawInstances.clear();
    }

    private String resolveClassName(long classObjId) {
        Long nameStringId = classNameStringIdByClassId.get(classObjId);
        if (nameStringId == null) {
            return "<unknown>";
        }
        String raw = strings.get(nameStringId);
        return raw == null ? "<unknown>" : raw.replace('/', '.');
    }

    /** Drops roots naming objects that were never written to the dump. */
    private Set<Long> resolveRoots() {
        Set<Long> resolved = new LinkedHashSet<>();
        for (long id : gcRoots) {
            if (objects.containsKey(id)) {
                resolved.add(id);
            }
        }
        return resolved;
    }
}
