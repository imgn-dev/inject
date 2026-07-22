package be.imgn.inject;

import static java.util.Objects.requireNonNull;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

import org.jspecify.annotations.Nullable;

/**
 * Factories for the parameterized {@link Type}s used as collection binding keys.
 *
 * <p>{@link ParameterizedType} specifies no equality contract, so a type produced here implements structural
 * {@code equals}/{@code hashCode} compatible with the JDK's own implementation: a key built from
 * {@code Types.setOf(X.class)} equals one captured through {@code new TypeLiteral<Set<X>>() {}}.
 */
public final class Types {

    private Types() {}

    /**
     * {@return the type {@code Set<elementType>}}
     *
     * @param elementType the set's element type
     */
    public static Type setOf(Class<?> elementType) {
        return new ParameterizedTypeImpl(Set.class, requireNonNull(elementType, "elementType"));
    }

    /**
     * {@return the type {@code Map<keyType, valueType>}}
     *
     * @param keyType the map's key type
     * @param valueType the map's value type
     */
    public static Type mapOf(Class<?> keyType, Class<?> valueType) {
        return new ParameterizedTypeImpl(
                Map.class, requireNonNull(keyType, "keyType"), requireNonNull(valueType, "valueType"));
    }

    /** Structural {@link ParameterizedType}; equality and hash code match the JDK implementation's semantics. */
    private static final class ParameterizedTypeImpl implements ParameterizedType {
        private final Class<?> rawType;
        private final Type[] actualTypeArguments;

        ParameterizedTypeImpl(Class<?> rawType, Type... actualTypeArguments) {
            this.rawType = rawType;
            this.actualTypeArguments = actualTypeArguments.clone();
        }

        @Override
        public Type[] getActualTypeArguments() {
            return actualTypeArguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public @Nullable Type getOwnerType() {
            return null;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ParameterizedType other
                    && Objects.equals(getOwnerType(), other.getOwnerType())
                    && rawType.equals(other.getRawType())
                    && Arrays.equals(actualTypeArguments, other.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            // Matches the JDK's ParameterizedTypeImpl so registry lookups work across implementations.
            return Arrays.hashCode(actualTypeArguments) ^ Objects.hashCode(getOwnerType()) ^ rawType.hashCode();
        }

        @Override
        public String toString() {
            StringJoiner arguments = new StringJoiner(", ", "<", ">");
            for (Type argument : actualTypeArguments) {
                arguments.add(argument.getTypeName());
            }
            return rawType.getName() + arguments;
        }
    }
}
