package be.imgn.inject;

import java.lang.reflect.Type;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A binding identity: a {@link Type} optionally combined with a {@link Qualifier}. A {@code null} qualifier denotes an
 * unqualified key. Identity is type + qualifier.
 *
 * @param <T> the bound type
 */
// T is a phantom parameter carried for call-site type-safety (getInstance/getProvider); it is intentionally
// not referenced by the class body, which stores the type as a java.lang.reflect.Type.
@SuppressWarnings("UnusedTypeParameter")
public final class Key<T> {
    private final Type type;
    private final @Nullable Qualifier qualifier;

    private Key(Type type, @Nullable Qualifier qualifier) {
        this.type = Objects.requireNonNull(type, "type");
        this.qualifier = qualifier;
    }

    /**
     * {@return an unqualified key for {@code type}}
     *
     * @param type the bound type
     * @param <T> the bound type
     */
    public static <T> Key<T> of(Class<T> type) {
        return new Key<>(type, null);
    }

    /**
     * {@return a key for {@code type} with an optional qualifier}
     *
     * @param type the bound type
     * @param qualifier the qualifier, or {@code null} for none
     * @param <T> the bound type
     */
    public static <T> Key<T> of(Class<T> type, @Nullable Qualifier qualifier) {
        return new Key<>(type, qualifier);
    }

    /**
     * {@return a key for a general {@link Type} with an optional qualifier}
     *
     * @param type the bound type (may be a parameterized type)
     * @param qualifier the qualifier, or {@code null} for none
     */
    public static Key<?> of(Type type, @Nullable Qualifier qualifier) {
        return new Key<>(type, qualifier);
    }

    /** {@return the bound type} */
    public Type type() {
        return type;
    }

    /** {@return the qualifier, or {@code null} if unqualified} */
    public @Nullable Qualifier qualifier() {
        return qualifier;
    }

    /** {@return whether this key carries a qualifier} */
    public boolean hasQualifier() {
        return qualifier != null;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Key<?> other && type.equals(other.type) && Objects.equals(qualifier, other.qualifier);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, qualifier);
    }

    @Override
    public String toString() {
        return qualifier == null
                ? "Key[" + type.getTypeName() + "]"
                : "Key[" + type.getTypeName() + " " + qualifier + "]";
    }
}
