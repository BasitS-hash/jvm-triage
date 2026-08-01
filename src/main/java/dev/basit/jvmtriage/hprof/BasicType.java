package dev.basit.jvmtriage.hprof;

/**
 * The primitive/reference type tags used throughout an HPROF dump.
 *
 * <p>The numeric tags come from the HPROF binary format, which is specified in
 * {@code src/hotspot/share/services/heapDumper.cpp} in the OpenJDK source tree.
 * Sizes are fixed by the format except for {@link #OBJECT}, whose width is the
 * identifier size declared in the file header — 4 bytes on a 32-bit dump or a
 * 64-bit dump written with compressed oops, 8 otherwise. That is why
 * {@link #sizeInBytes(int)} takes the identifier size rather than exposing a
 * constant.
 */
public enum BasicType {
    OBJECT(2, -1),
    BOOLEAN(4, 1),
    CHAR(5, 2),
    FLOAT(6, 4),
    DOUBLE(7, 8),
    BYTE(8, 1),
    SHORT(9, 2),
    INT(10, 4),
    LONG(11, 8);

    private static final BasicType[] BY_TAG = new BasicType[12];

    static {
        for (BasicType t : values()) {
            BY_TAG[t.tag] = t;
        }
    }

    private final int tag;
    private final int fixedSize;

    BasicType(int tag, int fixedSize) {
        this.tag = tag;
        this.fixedSize = fixedSize;
    }

    public int tag() {
        return tag;
    }

    /** True for {@link #OBJECT}, whose width depends on the dump's identifier size. */
    public boolean isReference() {
        return this == OBJECT;
    }

    /**
     * Width of a value of this type, in bytes.
     *
     * @param identifierSize identifier size declared in the dump header (4 or 8)
     */
    public int sizeInBytes(int identifierSize) {
        return fixedSize < 0 ? identifierSize : fixedSize;
    }

    /**
     * @throws HprofFormatException if {@code tag} is not a defined type tag. A dump
     *     is a binary stream with no internal checksums, so an unknown tag means we
     *     have lost framing; failing loudly beats silently mis-parsing the rest.
     */
    public static BasicType fromTag(int tag) {
        BasicType t = (tag >= 0 && tag < BY_TAG.length) ? BY_TAG[tag] : null;
        if (t == null) {
            throw new HprofFormatException("unknown basic type tag: " + tag);
        }
        return t;
    }
}
