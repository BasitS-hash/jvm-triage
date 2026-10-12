package dev.basit.jvmtriage.hprof;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/**
 * Positioned cursor over the bytes of an HPROF dump.
 *
 * <p>HPROF is big-endian throughout. Object identifiers are either 4 or 8 bytes
 * wide, declared once in the file header, so {@link #readId()} has to be
 * configured rather than fixed. Identifiers are returned as {@code long}
 * regardless of width; a 4-byte id is zero-extended, never sign-extended, since
 * an id is an address and {@code 0xFFFFFFFF} must not become {@code -1}.
 *
 * <p>The bytes are addressed as a list of segments rather than one buffer, because
 * a {@link ByteBuffer} cannot be longer than {@code Integer.MAX_VALUE} and a heap
 * dump from a leaking JVM routinely is. Positions are absolute {@code long}
 * offsets into the whole dump; segments are a power of two long, so locating one
 * is a shift and a mask.
 *
 * <p>Segments do not overlap. A multi-byte read that straddles a seam — a long, an
 * 8-byte id, part of a UTF-8 string, an array payload — is assembled a byte at a
 * time by {@link #readStraddling}, rather than relying on an overlap wide enough
 * to cover the largest atomic read. That keeps the choice of segment size free of
 * a correctness constraint, and costs one bounds comparison per read on the fast
 * path, which is where every read lands except the handful on a boundary.
 */
public final class HprofReader {

    /**
     * Segment size exponent for mapped dumps: 1 GiB each. Well under
     * {@code Integer.MAX_VALUE} so a segment always fits a single buffer, and large
     * enough that even a 32 GiB dump needs only a few dozen mappings.
     */
    static final int DEFAULT_SEGMENT_BITS = 30;

    private final ByteBuffer[] segments;
    private final int segmentBits;
    private final long offsetMask;
    private final long size;

    private long position;
    private int identifierSize = 8;

    public HprofReader(byte[] bytes) {
        this(ByteBuffer.wrap(bytes));
    }

    public HprofReader(ByteBuffer buffer) {
        // A buffer is already bounded by Integer.MAX_VALUE, so one segment covers it.
        this(new ByteBuffer[] {buffer.duplicate()}, 31, buffer.remaining());
    }

    private HprofReader(ByteBuffer[] segments, int segmentBits, long size) {
        if (segmentBits < 1 || segmentBits > 31) {
            throw new IllegalArgumentException("segment bits must be 1..31, got " + segmentBits);
        }
        for (ByteBuffer s : segments) {
            s.order(ByteOrder.BIG_ENDIAN);
        }
        this.segments = segments;
        this.segmentBits = segmentBits;
        this.offsetMask = (1L << segmentBits) - 1;
        this.size = size;
    }

    /** Maps a whole dump, however large, as 1 GiB segments. */
    public static HprofReader mapped(FileChannel channel) throws IOException {
        return mapped(channel, DEFAULT_SEGMENT_BITS);
    }

    /**
     * Maps a dump as segments of {@code 1 << segmentBits} bytes. Only tests pass a
     * size other than the default; they use a deliberately tiny one so a few hundred
     * bytes of dump spans many segments and the seam logic is actually exercised.
     */
    static HprofReader mapped(FileChannel channel, int segmentBits) throws IOException {
        long size = channel.size();
        long segmentSize = 1L << segmentBits;
        int count = (int) ((size + segmentSize - 1) / segmentSize);
        ByteBuffer[] segments = new ByteBuffer[Math.max(count, 1)];
        for (int i = 0; i < count; i++) {
            long start = (long) i * segmentSize;
            segments[i] = channel.map(
                    FileChannel.MapMode.READ_ONLY, start, Math.min(segmentSize, size - start));
        }
        if (count == 0) {
            segments[0] = ByteBuffer.allocate(0);
        }
        return new HprofReader(segments, segmentBits, size);
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

    public long position() {
        return position;
    }

    public void position(long newPosition) {
        if (newPosition < 0 || newPosition > size) {
            throw new HprofFormatException(
                    "position " + newPosition + " is outside a dump of " + size + " byte(s)");
        }
        this.position = newPosition;
    }

    public boolean hasRemaining() {
        return position < size;
    }

    public long remaining() {
        return size - position;
    }

    /** Reads one unsigned byte, widened to {@code int}. */
    public int readU1() {
        return (int) readUnsigned(1);
    }

    /** Reads one unsigned 2-byte value, widened to {@code int}. */
    public int readU2() {
        return (int) readUnsigned(2);
    }

    /**
     * Reads one unsigned 4-byte value.
     *
     * <p>Returned as {@code long} because record lengths and array counts use the
     * full unsigned range; narrowing to {@code int} here would turn a 3 GB length
     * into a negative number and send the caller backwards through the file.
     */
    public long readU4() {
        return readUnsigned(4);
    }

    public long readU8() {
        return readUnsigned(8);
    }

    public byte readI1() {
        return (byte) readUnsigned(1);
    }

    public short readI2() {
        return (short) readUnsigned(2);
    }

    public int readI4() {
        return (int) readUnsigned(4);
    }

    /** Reads an object identifier of the width declared in the file header. */
    public long readId() {
        return identifierSize == 4 ? readU4() : readU8();
    }

    public byte[] readBytes(long count) {
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new HprofFormatException(
                    "cannot read " + count + " byte(s) at offset " + position + " into one array");
        }
        require(count);
        byte[] out = new byte[(int) count];
        int written = 0;
        while (written < out.length) {
            ByteBuffer seg = segments[(int) (position >>> segmentBits)];
            int offset = (int) (position & offsetMask);
            int n = Math.min(out.length - written, seg.limit() - offset);
            seg.get(offset, out, written, n);
            written += n;
            position += n;
        }
        return out;
    }

    public void skip(long count) {
        if (count < 0) {
            throw new HprofFormatException("cannot skip a negative count: " + count);
        }
        require(count);
        position += count;
    }

    /**
     * Reads a NUL-terminated ASCII string, consuming the terminator.
     *
     * <p>A string running to the end of the dump without a terminator returns what
     * was there rather than throwing. The only caller is the header read, and the
     * overwhelmingly common reason for a missing terminator is that the file is not
     * a heap dump at all — so letting the version check report
     * {@code not an HPROF dump: header began with "..."} says far more than a
     * complaint about a terminator would.
     */
    public String readNullTerminatedString() {
        if (position >= size) {
            throw new HprofFormatException("unterminated string at offset " + position);
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        while (position < size) {
            int b = readU1();
            if (b == 0) {
                break;
            }
            raw.write(b);
        }
        return new String(raw.toByteArray(), StandardCharsets.UTF_8);
    }

    public String readUtf8(long length) {
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

    /**
     * Reads {@code width} big-endian bytes as an unsigned value. Callers narrow or
     * reinterpret: at width 8 the result is the raw bit pattern, which is what both
     * {@code readU8} and a {@code double}'s caller want.
     */
    private long readUnsigned(int width) {
        require(width);
        ByteBuffer seg = segments[(int) (position >>> segmentBits)];
        int offset = (int) (position & offsetMask);
        long value;
        if (offset + width <= seg.limit()) {
            value = switch (width) {
                case 1 -> seg.get(offset) & 0xFFL;
                case 2 -> seg.getShort(offset) & 0xFFFFL;
                case 4 -> seg.getInt(offset) & 0xFFFFFFFFL;
                default -> seg.getLong(offset);
            };
        } else {
            value = readStraddling(width);
        }
        position += width;
        return value;
    }

    /**
     * Assembles a value lying across a segment boundary one byte at a time. Shifting
     * left eight per byte gives big-endian order for every width, and at width 8 the
     * overflow out of the top is exactly the bit pattern wanted.
     */
    private long readStraddling(int width) {
        long value = 0;
        long at = position;
        for (int i = 0; i < width; i++, at++) {
            ByteBuffer seg = segments[(int) (at >>> segmentBits)];
            value = (value << 8) | (seg.get((int) (at & offsetMask)) & 0xFFL);
        }
        return value;
    }

    private void require(long count) {
        if (remaining() < count) {
            throw new HprofFormatException(
                    "truncated dump: needed " + count + " byte(s) at offset " + position
                            + " but only " + remaining() + " remain");
        }
    }
}
