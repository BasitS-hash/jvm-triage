# jvm-triage

Offline triage for JVM heap dumps and thread dumps. Answers the two questions you
actually have when a customer sends you a dump at 2am:

- **"What is eating the heap?"** — ranked by *retained* size, not shallow size.
- **"Why is it hung?"** — deadlock detection by cycle-finding on the wait-for graph.

No agent, no running JVM, no GUI. One jar, reads a file, prints an answer, exits
non-zero if it found a deadlock so you can wire it into CI or a monitoring hook.

```
$ jvm-triage heap leak.hprof
Heap dump: leak.hprof
  objects        36,922
  classes        831
  GC roots       889
  total shallow  16.9 MB
  unreachable    1,376

Top retained (what actually frees memory if collected)
  OBJECT                SHALLOW       RETAINED  % HEAP  CLASS
  0x7802fa750               0 B        15.8 MB   93.1%  class LeakDemo
  0x7802fa7e0              48 B        15.8 MB   93.1%  java.util.HashMap
  0x780560b70           32.0 KB        15.8 MB   93.1%  [Ljava.util.HashMap$Node;
```

That is a real dump from a real JVM. The program held 2000 × 8 KiB byte arrays in a
`static HashMap`; the tool attributes 93.1% of the heap to the class holding it.

```
$ jvm-triage threads hung.txt
DEADLOCK: 1 cycle(s) found

Cycle 1 (2 threads):
  "Thread-A" waits for 0x00000000ffff0002 (a com.example.Account) held by "Thread-B"
  "Thread-B" waits for 0x00000000ffff0001 (a com.example.Account) held by "Thread-A"
```

## Why retained size

Shallow size — an object's own bytes — is nearly useless for finding a leak. The
object at fault is almost never big. It is the map, cache, or listener list holding
everything else alive, and its own footprint is a few dozen bytes.

**Retained size** is the number that answers the question: if this object were
collected, how much memory comes back? That is the sum of everything it *dominates* —
every object whose only paths from a GC root run through it.

Computing it means building the dominator tree of the object graph. `jvm-triage` uses
the Cooper–Harvey–Kennedy iterative algorithm rather than Lengauer–Tarjan: it is
O(n·d) in graph depth rather than strictly near-linear, but on object graphs it
converges in a few passes and the implementation is short enough to verify by reading.

All GC roots attach to a single virtual root, so an object reachable from several
roots is correctly dominated by neither.

## Two bugs that only a real dump could find

The synthetic-dump tests all passed before this was ever pointed at a real JVM. Both
of these survived that suite and died on first contact with actual `jcmd` output:

**Static fields were not roots.** The parser read static fields into the class model
but never turned them into graph edges, and class objects were never materialised as
nodes. So everything held by a `static` collection — *the single most common cause of
a real Java leak* — looked like unreachable garbage. 12,776 objects were reported
unreachable and the actual leak did not appear in the report at all. Class objects are
now nodes whose outgoing edges are their static reference fields, and they are treated
as roots.

**Primitive arrays were unnamed.** `PRIMITIVE_ARRAY_DUMP` carries no class id, so
every `byte[]` and `char[]` reported as `<unknown>` — meaning the single largest line
of a leak report had no name on it. Synthetic class entries now give them one.

Fixing the first surfaced a third: typing a class object as an instance of *itself*
made every class object appear in the histogram as one extra instance of the class it
describes. Class objects are now instances of `java.lang.Class` and labelled
`class Foo` in reports — accurate type, useful label.

Both fixes are pinned by regression tests.

## Building and running

Requires JDK 21+.

```bash
mvn package
java -jar target/jvm-triage.jar heap  <dump.hprof> [limit]
java -jar target/jvm-triage.jar threads <dump.txt>
```

Capture dumps with the JDK's own tools:

```bash
jcmd <pid> GC.heap_dump /tmp/heap.hprof
jcmd <pid> Thread.print > /tmp/threads.txt
```

Exit codes: `0` analysed and clean, `1` deadlock found or dump unreadable, `2` usage.

## Scope and limits

- Heap dumps are memory-mapped as a list of 1 GiB segments, so there is no 2 GiB
  file-size ceiling. Three caveats remain, and they are real:
  - Mapping is still mapping. A 32 GiB dump needs 32 GiB of free *address space*,
    which is fine on any 64-bit JVM and impossible on a 32-bit one.
  - Nothing is read eagerly, but a full parse touches nearly every page, so the dump
    is effectively pulled through the page cache once. On a box with little free
    memory that means real I/O pressure, and the OS may evict other things to do it.
  - A single `PRIMITIVE_ARRAY_DUMP` or `OBJECT_ARRAY_DUMP` whose payload exceeds one
    segment is handled — reads that straddle a seam are assembled byte by byte — but
    the `readBytes` path still materialises an `INSTANCE_DUMP` field block as one
    `byte[]`, so an instance with more than 2 GiB of fields would be rejected. No JVM
    emits one.
- Class objects are treated as GC roots unconditionally. Strictly a class is only a
  root while its loader is reachable, but proving otherwise means modelling loader
  liveness, and guessing wrong would report live objects as collectable — much worse
  in a leak hunt than carrying a few unloadable classes.
- Thread-dump parsing is deliberately tolerant. Unrecognised lines are skipped rather
  than rejected, because dumps arrive truncated by log rotation or pasted out of
  tickets, and refusing to parse one is worse than parsing what is there.
- Deadlock detection covers monitors (`waiting to lock` / `locked`) and
  `java.util.concurrent` locks (`parking to wait for`, with ownership read from the
  trailing `Locked ownable synchronizers` block). That block matters: a `ReentrantLock`
  owner is never reported by a `locked` line, so without parsing it a j.u.c. deadlock
  yields a waiting edge, no owning edge, and no cycle — a real deadlock reported clean.
  It finds cycles the JVM's own "Found one Java-level deadlock" banner misses, including
  in dumps where that banner is absent entirely.

## Tests

50 tests, 93% line / 82% branch coverage.

```bash
mvn test
```

Parser tests build HPROF dumps byte by byte with a test-only writer, so the object
graph is known exactly and retained sizes are asserted against numbers worked out by
hand. Dominator tests use hand-built graphs — a diamond (proving the meet is right and
the shared object is not double-counted), cycles, self-reference, multi-root sharing,
and a 200,000-node chain that would overflow a recursive DFS.

The segmented reader is tested at test-only segment sizes of 4 to 64 bytes, so a few
hundred bytes of dump spans dozens of segments. One test slides the heap section
through every byte alignment against a 16-byte boundary; another computes the padding
that drops an 8-byte object id exactly four bytes either side of a seam, asserts it
really straddles, and then asserts it reads back as itself. A segmented reader
exercised only on files smaller than one segment is not tested at all.

## Attribution

Clean-room implementation. The HPROF binary format is read from its description in
OpenJDK's `src/hotspot/share/services/heapDumper.cpp`; the dominator algorithm is from
Cooper, Harvey & Kennedy, *A Simple, Fast Dominance Algorithm* (Rice CS-TR-06-33875).

The idea of retained-size-based leak reporting is not original — it is the model
[Eclipse MAT](https://eclipse.dev/mat/) established, and MAT remains the more complete
tool by a wide margin. No MAT code was consulted or copied; this is a from-scratch
implementation written to understand the problem, and it targets a narrower case: a
single self-contained jar you can run over a dump on a locked-down box with no GUI.

## Licence

MIT — see [LICENSE](LICENSE).
