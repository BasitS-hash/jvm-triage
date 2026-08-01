package dev.basit.jvmtriage.hprof;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Emits syntactically valid HPROF dumps for tests.
 *
 * <p>Hand-writing the bytes is what makes the parser tests meaningful: the object
 * graph is known exactly, so retained sizes and dominators can be asserted against
 * numbers worked out by hand rather than against whatever the parser happens to
 * produce. Fixed at an 8-byte identifier size unless told otherwise.
 */
public final class HprofBuilder {

    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(body);
    private final ByteArrayOutputStream heap = new ByteArrayOutputStream();
    private final DataOutputStream heapOut = new DataOutputStream(heap);
    private final int idSize;
    private long nextStringId = 1000;

    public HprofBuilder() {
        this(8);
    }

    public HprofBuilder(int idSize) {
        this.idSize = idSize;
    }

    // ---- top-level records --------------------------------------------------

    /** Writes a STRING_IN_UTF8 record and returns its id. */
    public long string(String value) {
        long id = nextStringId++;
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(rec);
        run(() -> {
            writeId(d, id);
            d.write(utf8);
        });
        topLevel(HprofTag.STRING_IN_UTF8, rec.toByteArray());
        return id;
    }

    /** Writes a LOAD_CLASS record binding a class id to a name. */
    public void loadClass(long classId, String name) {
        long nameId = string(name);
        ByteArrayOutputStream rec = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(rec);
        run(() -> {
            d.writeInt(1);          // class serial
            writeId(d, classId);
            d.writeInt(0);          // stack trace serial
            writeId(d, nameId);
        });
        topLevel(HprofTag.LOAD_CLASS, rec.toByteArray());
    }

    // ---- heap sub-records ---------------------------------------------------

    /**
     * Writes a CLASS_DUMP. {@code fields} alternate name then {@link BasicType},
     * declared by this class only.
     */
    public void classDump(long classId, long superId, int instanceSize, Object... fields) {
        run(() -> {
            heapOut.writeByte(HprofTag.CLASS_DUMP);
            writeId(heapOut, classId);
            heapOut.writeInt(0);            // stack trace serial
            writeId(heapOut, superId);
            writeId(heapOut, 0);            // class loader
            writeId(heapOut, 0);            // signers
            writeId(heapOut, 0);            // protection domain
            writeId(heapOut, 0);            // reserved
            writeId(heapOut, 0);            // reserved
            heapOut.writeInt(instanceSize);
            heapOut.writeShort(0);          // constant pool entries
            heapOut.writeShort(0);          // static fields
            heapOut.writeShort(fields.length / 2);
            for (int i = 0; i < fields.length; i += 2) {
                writeId(heapOut, string((String) fields[i]));
                heapOut.writeByte(((BasicType) fields[i + 1]).tag());
            }
        });
    }

    /** Writes a CLASS_DUMP carrying exactly one static field. */
    public void classDumpWithStatic(
            long classId, long superId, int instanceSize,
            String staticName, BasicType staticType, long staticValue) {
        run(() -> {
            long nameId = string(staticName);
            heapOut.writeByte(HprofTag.CLASS_DUMP);
            writeId(heapOut, classId);
            heapOut.writeInt(0);
            writeId(heapOut, superId);
            writeId(heapOut, 0);
            writeId(heapOut, 0);
            writeId(heapOut, 0);
            writeId(heapOut, 0);
            writeId(heapOut, 0);
            heapOut.writeInt(instanceSize);
            heapOut.writeShort(0);            // constant pool entries
            heapOut.writeShort(1);            // one static field
            writeId(heapOut, nameId);
            heapOut.writeByte(staticType.tag());
            writeValue(heapOut, staticType, staticValue);
            heapOut.writeShort(0);            // no instance fields
        });
    }

    /**
     * Writes an INSTANCE_DUMP whose field block is {@code fieldValues}, written in
     * declaration order using {@code fieldTypes} for widths.
     */
    public void instance(long objId, long classId, BasicType[] fieldTypes, long[] fieldValues) {
        run(() -> {
            ByteArrayOutputStream fb = new ByteArrayOutputStream();
            DataOutputStream f = new DataOutputStream(fb);
            for (int i = 0; i < fieldTypes.length; i++) {
                writeValue(f, fieldTypes[i], fieldValues[i]);
            }
            byte[] block = fb.toByteArray();

            heapOut.writeByte(HprofTag.INSTANCE_DUMP);
            writeId(heapOut, objId);
            heapOut.writeInt(0);
            writeId(heapOut, classId);
            heapOut.writeInt(block.length);
            heapOut.write(block);
        });
    }

    public void objectArray(long objId, long classId, long... elements) {
        run(() -> {
            heapOut.writeByte(HprofTag.OBJECT_ARRAY_DUMP);
            writeId(heapOut, objId);
            heapOut.writeInt(0);
            heapOut.writeInt(elements.length);
            writeId(heapOut, classId);
            for (long e : elements) {
                writeId(heapOut, e);
            }
        });
    }

    public void primitiveArray(long objId, BasicType type, int length) {
        run(() -> {
            heapOut.writeByte(HprofTag.PRIMITIVE_ARRAY_DUMP);
            writeId(heapOut, objId);
            heapOut.writeInt(0);
            heapOut.writeInt(length);
            heapOut.writeByte(type.tag());
            heapOut.write(new byte[length * type.sizeInBytes(idSize)]);
        });
    }

    public void rootStickyClass(long objId) {
        run(() -> {
            heapOut.writeByte(HprofTag.ROOT_STICKY_CLASS);
            writeId(heapOut, objId);
        });
    }

    public void rootJavaFrame(long objId) {
        run(() -> {
            heapOut.writeByte(HprofTag.ROOT_JAVA_FRAME);
            writeId(heapOut, objId);
            heapOut.writeInt(0);   // thread serial
            heapOut.writeInt(0);   // frame number
        });
    }

    /** Emits a raw sub-record tag, for negative tests. */
    public void rawHeapByte(int b) {
        run(() -> heapOut.writeByte(b));
    }

    // ---- assembly -----------------------------------------------------------

    public byte[] build() {
        return build("JAVA PROFILE 1.0.2");
    }

    public byte[] build(String version) {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(file);
        run(() -> {
            d.write(version.getBytes(StandardCharsets.US_ASCII));
            d.writeByte(0);
            d.writeInt(idSize);
            d.writeLong(System.currentTimeMillis());
            d.write(body.toByteArray());

            byte[] heapBytes = heap.toByteArray();
            if (heapBytes.length > 0) {
                d.writeByte(HprofTag.HEAP_DUMP_SEGMENT);
                d.writeInt(0);
                d.writeInt(heapBytes.length);
                d.write(heapBytes);
            }
        });
        return file.toByteArray();
    }

    // ---- helpers ------------------------------------------------------------

    private void topLevel(int tag, byte[] payload) {
        run(() -> {
            out.writeByte(tag);
            out.writeInt(0);
            out.writeInt(payload.length);
            out.write(payload);
        });
    }

    private void writeId(DataOutputStream d, long id) throws IOException {
        if (idSize == 4) {
            d.writeInt((int) id);
        } else {
            d.writeLong(id);
        }
    }

    private void writeValue(DataOutputStream d, BasicType type, long value) throws IOException {
        switch (type) {
            case OBJECT -> writeId(d, value);
            case BOOLEAN, BYTE -> d.writeByte((int) value);
            case CHAR, SHORT -> d.writeShort((int) value);
            case FLOAT, INT -> d.writeInt((int) value);
            case DOUBLE, LONG -> d.writeLong(value);
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private void run(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
