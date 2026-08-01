package dev.basit.jvmtriage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.basit.jvmtriage.hprof.BasicType;
import dev.basit.jvmtriage.hprof.HprofBuilder;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MainTest {

    private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();

    private int run(String... args) {
        PrintStream out = new PrintStream(outBuf, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(errBuf, true, StandardCharsets.UTF_8);
        return Main.run(args, out, err);
    }

    private String out() {
        return outBuf.toString(StandardCharsets.UTF_8);
    }

    private String err() {
        return errBuf.toString(StandardCharsets.UTF_8);
    }

    private static Path writeHeapDump(Path dir) throws IOException {
        HprofBuilder b = new HprofBuilder();
        b.loadClass(1L, "java/lang/Object");
        b.classDump(1L, 0L, 0);
        b.loadClass(2L, "com/example/Cache");
        b.classDumpWithStatic(2L, 0L, 0, "INSTANCE", BasicType.OBJECT, 100L);
        b.loadClass(3L, "com/example/Node");
        b.classDump(3L, 0L, 8, "payload", BasicType.OBJECT);
        b.instance(100L, 3L, new BasicType[] {BasicType.OBJECT}, new long[] {101L});
        b.primitiveArray(101L, BasicType.BYTE, 4096);

        Path f = dir.resolve("test.hprof");
        Files.write(f, b.build());
        return f;
    }

    @Test
    @DisplayName("no arguments prints usage and exits 2")
    void usageOnNoArgs() {
        assertEquals(2, run());
        assertTrue(err().contains("usage:"), err());
    }

    @Test
    @DisplayName("an unknown command exits 2")
    void unknownCommand() {
        assertEquals(2, run("frobnicate", "somefile"));
        assertTrue(err().contains("unknown command"), err());
    }

    @Test
    @DisplayName("heap report names the leak and its retained size")
    void heapReport(@TempDir Path dir) throws IOException {
        Path dump = writeHeapDump(dir);

        assertEquals(0, run("heap", dump.toString(), "5"));

        String o = out();
        assertTrue(o.contains("Top retained"), o);
        assertTrue(o.contains("com.example.Cache"), "the leaking class should be named:\n" + o);
        assertTrue(o.contains("byte[]"), "primitive arrays should be named:\n" + o);
    }

    @Test
    @DisplayName("a non-HPROF file is reported as an error, not a crash")
    void rejectsNonHprof(@TempDir Path dir) throws IOException {
        Path bogus = dir.resolve("notadump.hprof");
        Files.writeString(bogus, "this is not a heap dump, it is a haiku");

        assertEquals(1, run("heap", bogus.toString()));
        assertTrue(err().startsWith("error:"), err());
    }

    @Test
    @DisplayName("a missing file is reported as an error")
    void reportsMissingFile(@TempDir Path dir) {
        assertEquals(1, run("heap", dir.resolve("absent.hprof").toString()));
        assertTrue(err().contains("error reading"), err());
    }

    @Test
    @DisplayName("a clean thread dump exits 0")
    void cleanThreadDump(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("threads.txt");
        Files.writeString(f, """
                "main" #1 prio=5 tid=0x1 nid=0x1 runnable
                   java.lang.Thread.State: RUNNABLE
                	at com.example.App.main(App.java:7)
                """);

        assertEquals(0, run("threads", f.toString()));
        assertTrue(out().contains("No deadlock found."), out());
    }

    @Test
    @DisplayName("a deadlocked dump exits 1 so it can gate CI")
    void deadlockExitsNonZero(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("threads.txt");
        Files.writeString(f, """
                "A" #1 tid=0x1 nid=0x1 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.a(X.java:1)
                	- waiting to lock <0x2> (a X)
                	- locked <0x1> (a X)

                "B" #2 tid=0x2 nid=0x2 waiting for monitor entry
                   java.lang.Thread.State: BLOCKED (on object monitor)
                	at X.b(X.java:2)
                	- waiting to lock <0x1> (a X)
                	- locked <0x2> (a X)
                """);

        assertEquals(1, run("threads", f.toString()), "deadlock must be a non-zero exit");
        assertTrue(out().contains("DEADLOCK"), out());
        assertTrue(out().contains("\"A\""), out());
    }

    @Test
    @DisplayName("an unparsable limit falls back to the default instead of failing")
    void toleratesBadLimit(@TempDir Path dir) throws IOException {
        Path dump = writeHeapDump(dir);
        assertEquals(0, run("heap", dump.toString(), "not-a-number"));
        assertTrue(out().contains("Top retained"));
    }

    @Test
    @DisplayName("byte counts render in human units")
    void humanBytes() {
        assertEquals("512 B", Main.humanBytes(512));
        assertEquals("1.0 KB", Main.humanBytes(1024));
        assertEquals("1.0 MB", Main.humanBytes(1024L * 1024));
        assertEquals("1.5 GB", Main.humanBytes(1024L * 1024 * 1024 * 3 / 2));
    }
}
