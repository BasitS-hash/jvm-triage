package dev.basit.jvmtriage.heap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dominator and retained-size tests built on hand-constructed graphs.
 *
 * <p>Graphs are assembled directly rather than through the HPROF parser so a
 * failure here points at the algorithm and not at the format reader.
 */
class DominatorTreeTest {

    /** Builds a snapshot from explicit edges; every object gets shallow size 10. */
    private static Snapshot graph(Set<Long> roots, Map<Long, long[]> edges) {
        return graph(roots, edges, 10L);
    }

    private static Snapshot graph(Set<Long> roots, Map<Long, long[]> edges, long shallow) {
        Map<Long, JavaObject> objects = new LinkedHashMap<>();
        for (Map.Entry<Long, long[]> e : edges.entrySet()) {
            objects.put(e.getKey(), new JavaObject(
                    e.getKey(), 1L, shallow, e.getValue(), JavaObject.Kind.INSTANCE, 0));
        }
        return new Snapshot(Map.of(), objects, roots, 8);
    }

    @Test
    @DisplayName("a linear chain retains everything below it")
    void linearChain() {
        // root -> A -> B -> C
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {2L});
        edges.put(2L, new long[] {3L});
        edges.put(3L, new long[] {});
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertEquals(30L, d.retainedSize(1L), "A retains itself plus B and C");
        assertEquals(20L, d.retainedSize(2L), "B retains itself plus C");
        assertEquals(10L, d.retainedSize(3L), "C retains only itself");
    }

    @Test
    @DisplayName("a diamond gives the shared object to the branch point, not either arm")
    void diamondMeetsAtBranchPoint() {
        //        A
        //       / \
        //      B   C
        //       \ /
        //        D
        // D is reachable through B and through C, so neither dominates it; the
        // immediate dominator is A. If the meet were wrong, D's 10 bytes would be
        // double-counted into both B and C.
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {2L, 3L});
        edges.put(2L, new long[] {4L});
        edges.put(3L, new long[] {4L});
        edges.put(4L, new long[] {});
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertEquals(Long.valueOf(1L), d.immediateDominator(4L), "D is dominated by A");
        assertEquals(Long.valueOf(1L), d.immediateDominator(2L));
        assertEquals(Long.valueOf(1L), d.immediateDominator(3L));

        assertEquals(10L, d.retainedSize(2L), "freeing B alone frees nothing else");
        assertEquals(10L, d.retainedSize(3L), "freeing C alone frees nothing else");
        assertEquals(40L, d.retainedSize(1L), "A retains the whole diamond");
    }

    @Test
    @DisplayName("an object reachable from two roots is dominated by neither")
    void sharedBetweenTwoRoots() {
        // rootA -> X, rootB -> X
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {3L});
        edges.put(2L, new long[] {3L});
        edges.put(3L, new long[] {});
        Set<Long> roots = new LinkedHashSet<>();
        roots.add(1L);
        roots.add(2L);
        DominatorTree d = DominatorTree.build(graph(roots, edges));

        assertNull(d.immediateDominator(3L),
                "X hangs off the virtual root, so no real object dominates it");
        assertEquals(10L, d.retainedSize(1L), "dropping one root does not free X");
        assertEquals(10L, d.retainedSize(2L));
    }

    @Test
    @DisplayName("a cycle is retained by whatever dominates its entry point")
    void handlesCycles() {
        // root -> A -> B -> C -> B  (B and C form a cycle)
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {2L});
        edges.put(2L, new long[] {3L});
        edges.put(3L, new long[] {2L});
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertEquals(30L, d.retainedSize(1L), "A retains the whole cycle");
        assertTrue(d.isReachable(3L));
    }

    @Test
    @DisplayName("self-reference does not inflate retained size")
    void selfReference() {
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {1L});
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertEquals(10L, d.retainedSize(1L));
    }

    @Test
    @DisplayName("objects with no path from a root are reported unreachable")
    void detectsUnreachable() {
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {2L});
        edges.put(2L, new long[] {});
        edges.put(99L, new long[] {}); // island
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertTrue(d.isReachable(1L));
        assertFalse(d.isReachable(99L));
        assertEquals(java.util.List.of(99L), d.unreachableObjects());
        assertEquals(0L, d.retainedSize(99L), "unreachable objects retain nothing");
    }

    @Test
    @DisplayName("a deep chain does not overflow the stack")
    void deepGraphDoesNotOverflow() {
        // Recursion would die well before this depth; the explicit-stack DFS must not.
        int depth = 200_000;
        Map<Long, long[]> edges = new LinkedHashMap<>();
        for (long i = 1; i < depth; i++) {
            edges.put(i, new long[] {i + 1});
        }
        edges.put((long) depth, new long[] {});

        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges, 1L));

        assertEquals(depth, d.retainedSize(1L));
        assertTrue(d.isReachable((long) depth));
    }

    @Test
    @DisplayName("references to objects absent from the dump are ignored")
    void ignoresDanglingReferences() {
        Map<Long, long[]> edges = new LinkedHashMap<>();
        edges.put(1L, new long[] {2L, 12345L}); // 12345 was never dumped
        edges.put(2L, new long[] {});
        DominatorTree d = DominatorTree.build(graph(Set.of(1L), edges));

        assertEquals(20L, d.retainedSize(1L), "the missing object contributes nothing");
    }
}
