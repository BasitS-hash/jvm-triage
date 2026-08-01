package dev.basit.jvmtriage.hprof;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Positioned cursor over the bytes of an HPROF dump.
 *
 * <p>HPROF is big-endian throughout. Object identifiers are either 4 or 8 bytes
 * wide, declared once in the file header, so {@link #readId()} has to be
 * configured rather than fixed. Identifiers are returned as {@code long}
 * regardless of width; a 4-byte id is zero-extended, never sign-extended, since
 * an id is an address and {@code 0xFFFFFFFF} must not become {@code -1}.
 */
public final class HprofReader {

    private final ByteBuffer buf;
    private int identifierSize = 8;

    public HprofReader(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    public HprofReader(ByteBuffer buffer) {
        this.buf = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
    }

    public void identifierSize(int size) {
        if (size != 4 && size != 8) {
            throw new HprofFormatException("identifier size must be 4 or 8, got " + size);
        }
        this.identifierSize = size;
    }

    public int identifierSize() {
        return identifierSize;
    }

    public int position() {
        return buf.position();
    }

    public void position(int newPosition) {
        buf.position(newPosition);
    }

    public boolean hasRemaining() {
        return buf.hasRemaining();
    }

    public int remaining() {
        return buf.remaining();
    }

    /** Reads one unsigned byte, widened to {@code int}. */
    public int readU1() {
        require(1);
        return buf.get() & 0xFF;
    }

    /** Reads one unsigned 2-byte value, widened to {@code int}. */
    public int readU2() {
        require(2);
        return buf.getShort() & 0xFFFF;
    }

    /**
     * Reads one unsigned 4-byte value.
     *
     * <p>Returned as {@code long} because record lengths and array counts use the
     * full unsigned range; narrowing to {@code int} here would turn a 3 GB length
     * into a negative number and send the caller backwards through the file.
     */
    public long readU4() {
        require(4);
        return buf.getInt() & 0xFFFFFFFFL;
    }

    public long readU8() {
        require(8);
        return buf.getLong();
    }

    public byte readI1() {
        require(1);
        return buf.get();
    }

    public short readI2() {
        require(2);
        return buf.getShort();
    }

    public int readI4() {
        require(4);
        return buf.getInt();
    }

    /** Reads an object identifier of the width declared in the file header. */
    public long readId() {
        return identifierSize == 4 ? readU4() : readU8();
    }

    public byte[] readBytes(int count) {
        require(count);
        byte[] out = new byte[count];
        buf.get(out);
        return out;
    }

    public void skip(long count) {
        if (count < 0) {
            throw new HprofFormatException("cannot skip a negative count: " + count);
        }
        require(count);
        buf.position(buf.position() + (int) count);
    }

    /** Reads a NUL-terminated ASCII string, consuming the terminator. */
    public String readNullTerminatedString() {
        int start = buf.position();
        while (buf.hasRemaining() && buf.get() != 0) {
            // advance to the terminator
        }
        int end = buf.position() - 1;
        if (end < start) {
            throw new HprofFormatException("unterminated string at offset " + start);
        }
        byte[] raw = new byte[end - start];
        int mark = buf.position();
        buf.position(start);
        buf.get(raw);
        buf.position(mark);
        return new String(raw, StandardCharsets.UTF_8);
    }

    public String readUtf8(int length) {
        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    /**
     * Reads a value of {@code type}, returning it as a {@code long}. Reference and
     * integral values keep their numeric value; {@code float} and {@code double}
     * are returned as raw IEEE-754 bits so a single primitive return type covers
     * every case without boxing. Callers that need the real number apply
     * {@link Float#intBitsToFloat} or {@link Double#longBitsToDouble}.
     */
    public long readValue(BasicType type) {
        return switch (type) {
            case OBJECT -> readId();
            case BOOLEAN, BYTE -> readI1();
            case CHAR, SHORT -> readI2();
            case FLOAT, INT -> readI4();
            case DOUBLE, LONG -> readU8();
        };
    }

    private void require(long count) {
        if (buf.remaining() < count) {
            throw new HprofFormatException(
                    "truncated dump: needed " + count + " byte(s) at offset " + buf.position()
                            + " but only " + buf.remaining() + " remain");
        }
    }
}
