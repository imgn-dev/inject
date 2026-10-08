package be.imgn.inject;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/**
 * Represents a generic type {@code T}. Because Java erases generics, a plain {@code Class} cannot express a
 * parameterized type such as {@code List<String>}. Capture one by subclassing anonymously:
 *
 * <pre>{@code
 * TypeLiteral<List<String>> literal = new TypeLiteral<List<String>>() {};
 * }</pre>
 *
 * <p>For a raw type, prefer {@link #of(Class)}. Equality is by the underlying {@link Type}.
 *
 * @param <T> the represented type
 */
public class TypeLiteral<T> {
    private final Type type;

    /** Captures {@code T} from the anonymous subclass's type argument. */
    protected TypeLiteral() {
        Type superclass = getClass().getGenericSuperclass();
        if (!(superclass instanceof ParameterizedType parameterized)) {
            throw new IllegalArgumentException("TypeLiteral must be created with a type argument: " + getClass());
        }
        this.type = parameterized.getActualTypeArguments()[0];
    }

    TypeLiteral(Type type) {
        this.type = requireNonNull(type, "type");
    }

    /**
     * Returns a literal for the raw {@code type}.
     *
     * @param type the represented type
     * @param <T> the represented type
     * @return a literal for the raw {@code type}
     */
    public static <T> TypeLiteral<T> of(Class<T> type) {
        return new TypeLiteral<>((Type) requireNonNull(type, "type"));
    }

    /**
     * Returns a literal for a general {@link Type}.
     *
     * @param type the represented type (may be parameterized)
     * @return a literal for a general {@link Type}
     */
    public static TypeLiteral<?> of(Type type) {
        return new TypeLiteral<>(type);
    }

    /**
     * Returns the underlying reflective type.
     *
     * @return the underlying reflective type
     */
    public Type getType() {
        return type;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TypeLiteral<?> other && type.equals(other.type);
    }

    @Override
    public int hashCode() {
        return type.hashCode();
    }

    @Override
    public String toString() {
        return type.getTypeName();
    }
}
