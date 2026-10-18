package dev.basit.jvmtriage.hprof;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Streams an HPROF dump to a file, leaving chosen regions unwritten.
 *
 * <p>{@link HprofBuilder} assembles a dump in a {@code byte[]} and is the right tool
 * for every fixture of a few hundred bytes. It cannot produce one larger than
 * {@code Integer.MAX_VALUE} — which is precisely the size the segmented reader exists
 * to handle — so a test above that boundary needs a writer that never holds the whole
 * dump in memory.
 *
 * <p>Nearly all of a multi-gibibyte fixture is array payload and ignorable records that
 * the parser never looks at: it steps over an array body by advancing its position and
 * resynchronises on each record's declared length. {@link #hole(long)} therefore moves
 * the write cursor without writing anything, and on a filesystem with sparse-file
 * support the gap stays unallocated. A 2.75 GiB fixture then costs a few kibibytes of
 * disk and well under a second to create. Writing the zeros for real would cost minutes
 * and prove nothing extra, because the reader's {@code mmap} sees a hole as zeros either
 * way. On a filesystem without sparse files the fixture still writes correctly — it just
 * consumes its full length, which is why callers check free space first.
 *
 * <p>Identifiers are 8 bytes. HotSpot writes {@code sizeof(void*)} as the dump's
 * identifier size, so every dump big enough to matter here has 8-byte ids; the 4-byte
 * case is covered by {@link HprofBuilder} fixtures that fit in memory.
 *
 * <p>One staging buffer serves both uses: shape a record with the {@code u1}/{@code id}
 * writers, then either {@link #flush()} it to the file or {@link #take()} it as bytes so
 * its length can be declared in a header that has to be written first.
 */
final class SparseHprofWriter implements Closeable {

    private final FileChannel channel;
    private final ByteArrayOutputStream staged = new ByteArrayOutputStream();
    private final DataOutputStream out = new DataOutputStream(staged);

    private long position;

    SparseHprofWriter(Path file) throws IOException {
        this.channel = FileChannel.open(
                file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /** Absolute offset the next byte would be written at. */
    long position() {
        return position;
    }

    SparseHprofWriter u1(int value) {
        return run(() -> out.writeByte(value));
    }

    SparseHprofWriter u2(int value) {
        return run(() -> out.writeShort(value));
    }

    SparseHprofWriter u4(long value) {
        return run(() -> out.writeInt((int) value));
    }

    SparseHprofWriter u8(long value) {
        return run(() -> out.writeLong(value));
    }

    SparseHprofWriter id(long value) {
        return run(() -> out.writeLong(value));
    }

    SparseHprofWriter bytes(byte[] value) {
        return run(() -> out.write(value));
    }

    /** Writes the staged bytes at the cursor and advances it. */
    void flush() throws IOException {
        ByteBuffer buf = ByteBuffer.wrap(staged.toByteArray());
        staged.reset();
        while (buf.hasRemaining()) {
            position += channel.write(buf, position);
        }
    }

    /** Returns the staged bytes without writing them, so their length can be declared. */
    byte[] take() {
        byte[] result = staged.toByteArray();
        staged.reset();
        return result;
    }

    /** Advances the cursor over {@code count} bytes the file never stores explicitly. */
    void hole(long count) {
        if (count < 0) {
            throw new IllegalArgumentException("negative hole: " + count);
        }
        position += count;
    }

    /**
     * Extends the file so a trailing hole counts towards its length, and returns the
     * final size. Writes one byte only when the cursor is beyond the last real write;
     * otherwise it would overwrite content that is already there.
     */
    long finish() throws IOException {
        flush();
        if (position > 0 && channel.size() < position) {
            channel.write(ByteBuffer.allocate(1), position - 1);
        }
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private SparseHprofWriter run(IoAction action) {
        try {
            action.run();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return this;
    }
}
