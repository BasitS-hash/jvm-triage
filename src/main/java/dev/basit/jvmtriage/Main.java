package dev.basit.jvmtriage;

import dev.basit.jvmtriage.heap.DominatorTree;
import dev.basit.jvmtriage.heap.LeakSuspects;
import dev.basit.jvmtriage.heap.Snapshot;
import dev.basit.jvmtriage.hprof.HprofFormatException;
import dev.basit.jvmtriage.hprof.HprofParser;
import dev.basit.jvmtriage.threads.DeadlockDetector;
import dev.basit.jvmtriage.threads.ThreadDumpParser;
import dev.basit.jvmtriage.threads.ThreadInfo;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Command-line entry point. */
public final class Main {

    private static final int DEFAULT_LIMIT = 15;

    public static void main(String[] args) {
        int exit = run(args, System.out, System.err);
        System.exit(exit);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length < 2) {
            usage(err);
            return 2;
        }
        String command = args[0];
        Path file = Path.of(args[1]);
        int limit = args.length >= 3 ? parseLimit(args[2]) : DEFAULT_LIMIT;

        try {
            return switch (command) {
                case "heap" -> heap(file, limit, out);
                case "threads" -> threads(file, out);
                default -> {
                    err.println("unknown command: " + command);
                    usage(err);
                    yield 2;
                }
            };
        } catch (HprofFormatException e) {
            err.println("error: " + e.getMessage());
            return 1;
        } catch (IOException e) {
            err.println("error reading " + file + ": " + e.getMessage());
            return 1;
        }
    }

    private static int heap(Path file, int limit, PrintStream out) throws IOException {
        Snapshot snapshot = HprofParser.parse(file);
        DominatorTree dominators = DominatorTree.build(snapshot);

        out.printf("Heap dump: %s%n", file.getFileName());
        out.printf("  objects        %,d%n", snapshot.objects().size());
        out.printf("  classes        %,d%n", snapshot.classes().size());
        out.printf("  GC roots       %,d%n", snapshot.gcRoots().size());
        out.printf("  total shallow  %s%n", humanBytes(snapshot.totalShallowSize()));
        out.printf("  unreachable    %,d%n", dominators.unreachableObjects().size());
        out.println();

        out.println("Top retained (what actually frees memory if collected)");
        out.printf("  %-14s %14s %14s %7s  %s%n",
                "OBJECT", "SHALLOW", "RETAINED", "% HEAP", "CLASS");
        for (LeakSuspects.Suspect s
                : LeakSuspects.topByRetainedSize(snapshot, dominators, limit)) {
            out.printf("  0x%-12x %14s %14s %6.1f%%  %s%n",
                    s.objectId(), humanBytes(s.shallowSize()), humanBytes(s.retainedSize()),
                    s.percentOfHeap(), s.className());
        }
        out.println();

        out.println("Largest classes by total shallow size");
        out.printf("  %14s %12s  %s%n", "BYTES", "INSTANCES", "CLASS");
        for (LeakSuspects.ClassTotal c : LeakSuspects.histogram(snapshot, limit)) {
            out.printf("  %14s %12d  %s%n",
                    humanBytes(c.totalShallowSize()), c.instanceCount(), c.className());
        }
        return 0;
    }

    private static int threads(Path file, PrintStream out) throws IOException {
        List<ThreadInfo> threads = ThreadDumpParser.parse(Files.readString(file));
        List<DeadlockDetector.Deadlock> deadlocks = DeadlockDetector.findDeadlocks(threads);

        out.printf("Thread dump: %s%n", file.getFileName());
        out.printf("  threads   %d%n", threads.size());
        out.printf("  blocked   %d%n", threads.stream().filter(ThreadInfo::isBlocked).count());
        out.println();

        if (deadlocks.isEmpty()) {
            out.println("No deadlock found.");
            return 0;
        }

        out.printf("DEADLOCK: %d cycle(s) found%n%n", deadlocks.size());
        int n = 1;
        for (DeadlockDetector.Deadlock d : deadlocks) {
            out.printf("Cycle %d (%d threads):%n", n++, d.cycle().size());
            out.print(d.describe());
            out.println();
            for (ThreadInfo t : d.cycle()) {
                out.printf("  \"%s\" at %s%n", t.name(), t.topFrame());
            }
            out.println();
        }
        // Non-zero exit so this is usable as a CI or monitoring gate.
        return 1;
    }

    private static int parseLimit(String raw) {
        try {
            int v = Integer.parseInt(raw);
            return v > 0 ? v : DEFAULT_LIMIT;
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    static String humanBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < units.length - 1) {
            v /= 1024;
            i++;
        }
        return String.format("%.1f %s", v, units[i]);
    }

    private static void usage(PrintStream err) {
        err.println("""
                jvm-triage — offline triage for JVM heap and thread dumps

                usage:
                  jvm-triage heap    <dump.hprof> [limit]   leak suspects by retained size
                  jvm-triage threads <dump.txt>             deadlock detection

                exit codes:
                  0  analysed, nothing wrong found
                  1  deadlock found, or the dump could not be read
                  2  bad usage
                """);
    }

    private Main() {}
}
