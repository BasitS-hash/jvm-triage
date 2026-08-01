package dev.basit.jvmtriage.heap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The parsed contents of a heap dump: every object, every class, and the GC roots. */
public final class Snapshot {

    private final Map<Long, ClassInfo> classes;
    private final Map<Long, JavaObject> objects;
    private final Set<Long> gcRoots;
    private final int identifierSize;

    public Snapshot(
            Map<Long, ClassInfo> classes,
            Map<Long, JavaObject> objects,
            Set<Long> gcRoots,
            int identifierSize) {
        this.classes = Map.copyOf(classes);
        this.objects = Map.copyOf(objects);
        // Retain iteration order so a dominator run over the same dump is reproducible.
        this.gcRoots = java.util.Collections.unmodifiableSet(new LinkedHashSet<>(gcRoots));
        this.identifierSize = identifierSize;
    }

    public Map<Long, ClassInfo> classes() {
        return classes;
    }

    public Map<Long, JavaObject> objects() {
        return objects;
    }

    /**
     * GC roots, filtered to those that actually resolve to an object in this dump.
     *
     * <p>Real dumps routinely name roots that were never written out — a thread
     * object for a thread that died mid-dump, for instance. Keeping the dangling
     * ids would make reachability counts disagree with the object table.
     */
    public Set<Long> gcRoots() {
        return gcRoots;
    }

    public int identifierSize() {
        return identifierSize;
    }

    public ClassInfo classOf(JavaObject obj) {
        return classes.get(obj.classId());
    }

    /**
     * Human-readable label for an object, or {@code "<unknown>"} if its class is
     * absent from the dump.
     *
     * <p>A class object is typed as an instance of {@code java.lang.Class} so
     * histogram counts stay honest, but reporting it that way would be useless — the
     * whole point of seeing it at the top of a leak report is knowing *which* class
     * holds the static. Those are labelled {@code "class com.example.Foo"}, which
     * keeps the type accurate and the label informative.
     */
    public String classNameOf(JavaObject obj) {
        ClassInfo self = classes.get(obj.id());
        if (self != null) {
            return "class " + self.name();
        }
        ClassInfo ci = classes.get(obj.classId());
        return ci == null ? "<unknown>" : ci.name();
    }

    public long totalShallowSize() {
        long total = 0;
        for (JavaObject o : objects.values()) {
            total += o.shallowSize();
        }
        return total;
    }

    /** Every object of the named class. */
    public List<JavaObject> instancesOf(String className) {
        Collection<Long> ids = new ArrayList<>();
        for (ClassInfo ci : classes.values()) {
            if (ci.name().equals(className)) {
                ids.add(ci.id());
            }
        }
        List<JavaObject> out = new ArrayList<>();
        for (JavaObject o : objects.values()) {
            if (ids.contains(o.classId())) {
                out.add(o);
            }
        }
        return out;
    }
}
