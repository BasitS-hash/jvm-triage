package dev.basit.jvmtriage.hprof;

/**
 * Record tags from the HPROF binary format.
 *
 * <p>Split into two namespaces that happen to overlap numerically: top-level
 * records that appear directly in the file, and the sub-records that appear only
 * inside a {@code HEAP_DUMP} or {@code HEAP_DUMP_SEGMENT} body. {@code 0x01} means
 * {@code STRING_IN_UTF8} at the top level but {@code ROOT_JNI_GLOBAL} inside a heap
 * segment, so the two sets are never interchangeable.
 */
public final class HprofTag {

    private HprofTag() {}

    // ---- Top-level records -------------------------------------------------
    public static final int STRING_IN_UTF8 = 0x01;
    public static final int LOAD_CLASS = 0x02;
    public static final int UNLOAD_CLASS = 0x03;
    public static final int STACK_FRAME = 0x04;
    public static final int STACK_TRACE = 0x05;
    public static final int ALLOC_SITES = 0x06;
    public static final int HEAP_SUMMARY = 0x07;
    public static final int START_THREAD = 0x0A;
    public static final int END_THREAD = 0x0B;
    public static final int HEAP_DUMP = 0x0C;
    public static final int CONTROL_SETTINGS = 0x0E;
    public static final int HEAP_DUMP_SEGMENT = 0x1C;
    public static final int HEAP_DUMP_END = 0x2C;

    // ---- Heap-dump sub-records ---------------------------------------------
    public static final int ROOT_UNKNOWN = 0xFF;
    public static final int ROOT_JNI_GLOBAL = 0x01;
    public static final int ROOT_JNI_LOCAL = 0x02;
    public static final int ROOT_JAVA_FRAME = 0x03;
    public static final int ROOT_NATIVE_STACK = 0x04;
    public static final int ROOT_STICKY_CLASS = 0x05;
    public static final int ROOT_THREAD_BLOCK = 0x06;
    public static final int ROOT_MONITOR_USED = 0x07;
    public static final int ROOT_THREAD_OBJ = 0x08;
    public static final int CLASS_DUMP = 0x20;
    public static final int INSTANCE_DUMP = 0x21;
    public static final int OBJECT_ARRAY_DUMP = 0x22;
    public static final int PRIMITIVE_ARRAY_DUMP = 0x23;
}
