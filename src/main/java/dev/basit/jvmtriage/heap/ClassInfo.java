package dev.basit.jvmtriage.heap;

import dev.basit.jvmtriage.hprof.BasicType;
import java.util.List;

/**
 * A class as described by a {@code CLASS_DUMP} record.
 *
 * <p>{@link #instanceFields()} holds only the fields declared by this class, not
 * inherited ones — that is how HPROF stores them. An instance's field block is
 * laid out most-derived-first, so decoding one means walking {@link #superClassId()}
 * and concatenating each level's declared fields in order.
 */
public record ClassInfo(
        long id,
        String name,
        long superClassId,
        long classLoaderId,
        int instanceSize,
        List<FieldInfo> instanceFields,
        List<StaticField> staticFields) {

    public ClassInfo {
        instanceFields = List.copyOf(instanceFields);
        staticFields = List.copyOf(staticFields);
    }

    /** A field declared by a class: its name and type, but not its value. */
    public record FieldInfo(String name, BasicType type) {}

    /** A static field, which carries its value directly in the class record. */
    public record StaticField(String name, BasicType type, long value) {}
}
