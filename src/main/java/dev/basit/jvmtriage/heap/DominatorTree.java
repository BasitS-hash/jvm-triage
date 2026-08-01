package dev.basit.jvmtriage.heap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immediate dominators and retained sizes for the object graph.
 *
 * <p>Retained size is the question a leak hunt actually asks: <em>if this object
 * were collected, how much memory comes back?</em> That is the sum of the shallow
 * sizes of every object it dominates — every object whose only paths from a GC
 * root pass through it. Shallow size alone cannot answer it, because a
 * {@code HashMap} holding a gigabyte is itself only a few dozen bytes.
 *
 * <p>Dominators are computed with the Cooper–Harvey–Kennedy iterative algorithm
 * ("A Simple, Fast Dominance Algorithm", Rice CS-TR-06-33875) rather than
 * Lengauer–Tarjan. It is O(n·d) where d is the graph's depth rather than strictly
 * near-linear, but on object graphs it runs in a handful of passes and the
 * implementation is short enough to verify by reading.
 *
 * <p>All GC roots are attached to a single virtual root at index 0, which makes an
 * object reachable from several roots correctly dominated by the virtual root
 * rather than by any one of them.
 */
public final class DominatorTree {

    private static final int VIRTUAL_ROOT = 0;
    private static final int UNDEFINED = -1;

    private final long[] idsByIndex;
    private final Map<Long, Integer> indexById;
    private final int[] idom;
    private final long[] retainedSize;
    private final int[] rpoNumber;

    private DominatorTree(
            long[] idsByIndex,
            Map<Long, Integer> indexById,
            int[] idom,
            long[] retainedSize,
            int[] rpoNumber) {
        this.idsByIndex = idsByIndex;
        this.indexById = indexById;
        this.idom = idom;
        this.retainedSize = retainedSize;
        this.rpoNumber = rpoNumber;
    }

    public static DominatorTree build(Snapshot snapshot) {
        // Index 0 is the virtual root; real objects start at 1.
        Map<Long, JavaObject> objects = snapshot.objects();
        int n = objects.size() + 1;

        long[] idsByIndex = new long[n];
        Map<Long, Integer> indexById = new HashMap<>(n * 2);
        int next = 1;
        for (long id : objects.keySet()) {
            idsByIndex[next] = id;
            indexById.put(id, next);
            next++;
        }

        int[][] successors = buildSuccessors(snapshot, objects, indexById, n);
        int[] order = reversePostOrder(successors, n);
        int[] rpoNumber = new int[n];
        Arrays.fill(rpoNumber, UNDEFINED);
        for (int i = 0; i < order.length; i++) {
            rpoNumber[order[i]] = i;
        }

        int[][] predecessors = invert(successors, n);
        int[] idom = computeIdom(order, rpoNumber, predecessors, n);
        long[] retained =
                computeRetained(snapshot, objects, idsByIndex, idom, order, rpoNumber, n);

        return new DominatorTree(idsByIndex, indexById, idom, retained, rpoNumber);
    }

    private static int[][] buildSuccessors(
            Snapshot snapshot, Map<Long, JavaObject> objects,
            Map<Long, Integer> indexById, int n) {

        int[][] successors = new int[n][];

        // Virtual root -> every GC root.
        int[] rootEdges = new int[snapshot.gcRoots().size()];
        int r = 0;
        for (long rootId : snapshot.gcRoots()) {
            Integer idx = indexById.get(rootId);
            if (idx != null) {
                rootEdges[r++] = idx;
            }
        }
        successors[VIRTUAL_ROOT] = Arrays.copyOf(rootEdges, r);

        for (Map.Entry<Long, JavaObject> e : objects.entrySet()) {
            int from = indexById.get(e.getKey());
            long[] refs = e.getValue().references();
            int[] edges = new int[refs.length];
            int kept = 0;
            for (long ref : refs) {
                Integer to = indexById.get(ref);
                // A reference can name an object absent from the dump; skip it
                // rather than inventing a node for it.
                if (to != null) {
                    edges[kept++] = to;
                }
            }
            successors[from] = Arrays.copyOf(edges, kept);
        }
        return successors;
    }

    /**
     * Reverse post-order from the virtual root, computed with an explicit stack.
     *
     * <p>Recursion is not an option here: object graphs routinely nest deeper than
     * the default JVM stack (a long linked list is enough), and a
     * {@code StackOverflowError} while analysing someone's OOM dump is a poor
     * outcome. Unreachable objects never enter the order and are handled separately.
     */
    private static int[] reversePostOrder(int[][] successors, int n) {
        boolean[] visited = new boolean[n];
        int[] postOrder = new int[n];
        int postCount = 0;

        int[] nodeStack = new int[n];
        int[] edgeStack = new int[n];
        int sp = 0;

        nodeStack[0] = VIRTUAL_ROOT;
        edgeStack[0] = 0;
        visited[VIRTUAL_ROOT] = true;
        sp = 1;

        while (sp > 0) {
            int node = nodeStack[sp - 1];
            int edge = edgeStack[sp - 1];
            int[] succ = successors[node];

            if (succ != null && edge < succ.length) {
                edgeStack[sp - 1] = edge + 1;
                int child = succ[edge];
                if (!visited[child]) {
                    visited[child] = true;
                    nodeStack[sp] = child;
                    edgeStack[sp] = 0;
                    sp++;
                }
            } else {
                postOrder[postCount++] = node;
                sp--;
            }
        }

        int[] rpo = new int[postCount];
        for (int i = 0; i < postCount; i++) {
            rpo[i] = postOrder[postCount - 1 - i];
        }
        return rpo;
    }

    private static int[][] invert(int[][] successors, int n) {
        int[] counts = new int[n];
        for (int[] succ : successors) {
            if (succ == null) continue;
            for (int to : succ) {
                counts[to]++;
            }
        }
        int[][] preds = new int[n][];
        for (int i = 0; i < n; i++) {
            preds[i] = new int[counts[i]];
        }
        int[] fill = new int[n];
        for (int from = 0; from < n; from++) {
            int[] succ = successors[from];
            if (succ == null) continue;
            for (int to : succ) {
                preds[to][fill[to]++] = from;
            }
        }
        return preds;
    }

    private static int[] computeIdom(
            int[] order, int[] rpoNumber, int[][] predecessors, int n) {

        int[] idom = new int[n];
        Arrays.fill(idom, UNDEFINED);
        idom[VIRTUAL_ROOT] = VIRTUAL_ROOT;

        boolean changed = true;
        while (changed) {
            changed = false;
            for (int node : order) {
                if (node == VIRTUAL_ROOT) continue;

                int newIdom = UNDEFINED;
                for (int pred : predecessors[node]) {
                    // Only predecessors already given a dominator can contribute.
                    if (idom[pred] == UNDEFINED) continue;
                    newIdom = (newIdom == UNDEFINED)
                            ? pred
                            : intersect(pred, newIdom, idom, rpoNumber);
                }
                if (newIdom != UNDEFINED && idom[node] != newIdom) {
                    idom[node] = newIdom;
                    changed = true;
                }
            }
        }
        return idom;
    }

    /** Walks both nodes up the partially-built dominator tree to their meet. */
    private static int intersect(int a, int b, int[] idom, int[] rpoNumber) {
        while (a != b) {
            while (rpoNumber[a] > rpoNumber[b]) {
                a = idom[a];
            }
            while (rpoNumber[b] > rpoNumber[a]) {
                b = idom[b];
            }
        }
        return a;
    }

    /**
     * Accumulates shallow sizes up the dominator tree.
     *
     * <p>Walking the RPO backwards visits every node before its immediate
     * dominator, because a dominator always precedes what it dominates in reverse
     * post-order. One reverse pass therefore suffices — no explicit tree traversal
     * is needed.
     */
    private static long[] computeRetained(
            Snapshot snapshot, Map<Long, JavaObject> objects,
            long[] idsByIndex, int[] idom, int[] order, int[] rpoNumber, int n) {

        long[] retained = new long[n];
        for (int i = 1; i < n; i++) {
            // Unreachable objects are already garbage: freeing anything does not
            // "reclaim" them, so they retain nothing and must stay at zero rather
            // than seeding their own shallow size into a leak report.
            if (rpoNumber[i] == UNDEFINED) {
                continue;
            }
            JavaObject o = objects.get(idsByIndex[i]);
            retained[i] = o == null ? 0 : o.shallowSize();
        }

        for (int i = order.length - 1; i >= 0; i--) {
            int node = order[i];
            if (node == VIRTUAL_ROOT) continue;
            int parent = idom[node];
            if (parent != UNDEFINED && parent != node) {
                retained[parent] += retained[node];
            }
        }
        return retained;
    }

    /** Retained bytes for {@code objectId}, or 0 if it is absent or unreachable. */
    public long retainedSize(long objectId) {
        Integer idx = indexById.get(objectId);
        return idx == null ? 0L : retainedSize[idx];
    }

    /** The object that immediately dominates {@code objectId}, if any. */
    public Long immediateDominator(long objectId) {
        Integer idx = indexById.get(objectId);
        if (idx == null) return null;
        int d = idom[idx];
        if (d == UNDEFINED || d == VIRTUAL_ROOT || d == idx) return null;
        return idsByIndex[d];
    }

    /** True if the object is reachable from any GC root. */
    public boolean isReachable(long objectId) {
        Integer idx = indexById.get(objectId);
        return idx != null && rpoNumber[idx] != UNDEFINED;
    }

    /** Object ids never reached from a GC root — dump artefacts or uncollected garbage. */
    public List<Long> unreachableObjects() {
        List<Long> out = new ArrayList<>();
        for (int i = 1; i < idsByIndex.length; i++) {
            if (rpoNumber[i] == UNDEFINED) {
                out.add(idsByIndex[i]);
            }
        }
        return out;
    }
}
