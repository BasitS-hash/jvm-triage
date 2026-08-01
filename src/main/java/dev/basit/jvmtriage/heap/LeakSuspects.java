package dev.basit.jvmtriage.heap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Ranks the objects and classes most likely to be responsible for heap growth.
 *
 * <p>Suspects are ranked by retained size, not shallow size. The object at fault in
 * a leak is almost never large itself — it is the map, list, or cache holding
 * everything else alive, and its own footprint is a few dozen bytes.
 */
public final class LeakSuspects {

    private LeakSuspects() {}

    /** One candidate: an object, what it is, and how much it holds alive. */
    public record Suspect(
            long objectId,
            String className,
            long shallowSize,
            long retainedSize,
            double percentOfHeap) {}

    /** Aggregate footprint of every instance of one class. */
    public record ClassTotal(
            String className, long instanceCount, long totalShallowSize) {}

    /**
     * The {@code limit} objects with the largest retained size.
     *
     * <p>Dominator roots — objects whose immediate dominator is the virtual root —
     * are not filtered out here. They are usually the interesting ones, but a
     * caller charting a single subtree still wants nested candidates available.
     */
    public static List<Suspect> topByRetainedSize(
            Snapshot snapshot, DominatorTree dominators, int limit) {

        long heapTotal = snapshot.totalShallowSize();
        List<Suspect> suspects = new ArrayList<>();

        for (JavaObject obj : snapshot.objects().values()) {
            long retained = dominators.retainedSize(obj.id());
            if (retained <= 0) {
                continue;
            }
            double pct = heapTotal == 0 ? 0.0 : (retained * 100.0) / heapTotal;
            suspects.add(new Suspect(
                    obj.id(), snapshot.classNameOf(obj), obj.shallowSize(), retained, pct));
        }

        suspects.sort(Comparator.comparingLong(Suspect::retainedSize).reversed());
        return suspects.size() <= limit ? suspects : new ArrayList<>(suspects.subList(0, limit));
    }

    /** Per-class instance counts and total shallow bytes, largest first. */
    public static List<ClassTotal> histogram(Snapshot snapshot, int limit) {
        Map<String, long[]> byClass = new HashMap<>(); // name -> {count, bytes}

        for (JavaObject obj : snapshot.objects().values()) {
            String name = snapshot.classNameOf(obj);
            long[] agg = byClass.computeIfAbsent(name, k -> new long[2]);
            agg[0]++;
            agg[1] += obj.shallowSize();
        }

        List<ClassTotal> totals = new ArrayList<>(byClass.size());
        for (Map.Entry<String, long[]> e : byClass.entrySet()) {
            totals.add(new ClassTotal(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        totals.sort(Comparator.comparingLong(ClassTotal::totalShallowSize).reversed());
        return totals.size() <= limit ? totals : new ArrayList<>(totals.subList(0, limit));
    }

    /**
     * Walks the dominator chain from an object up to a GC root.
     *
     * <p>This is the "why is this still alive?" answer: each step names the object
     * whose reference is keeping the next one from being collected.
     */
    public static List<Long> pathToRoot(DominatorTree dominators, long objectId) {
        List<Long> path = new ArrayList<>();
        Long cursor = objectId;
        // Bound the walk; a malformed tree must not spin here.
        int guard = 0;
        while (cursor != null && guard++ < 10_000) {
            path.add(cursor);
            cursor = dominators.immediateDominator(cursor);
        }
        return path;
    }
}
