package dev.basit.jvmtriage.heap;

/**
 * One live object in the dump: an instance, an object array, or a primitive array.
 *
 * <p>{@link #references()} is already filtered to non-null outgoing references, so
 * graph code never has to skip zeros. {@link #shallowSize()} is the object's own
 * bytes only — the field block for an instance, the element storage for an array —
 * and excludes anything it points at. Retained size, the number that actually
 * matters for a leak hunt, is computed later from the dominator tree.
 */
public record JavaObject(
        long id,
        long classId,
        long shallowSize,
        long[] references,
        Kind kind,
        int arrayLength) {

    public enum Kind {
        INSTANCE,
        OBJECT_ARRAY,
        PRIMITIVE_ARRAY
    }

    public boolean isArray() {
        return kind != Kind.INSTANCE;
    }
}
