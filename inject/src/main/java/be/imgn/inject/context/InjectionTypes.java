package be.imgn.inject.context;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Map;
import java.util.Objects;

/** Erasure and primitive boxing for checking resolved values against their declared injection types. */
final class InjectionTypes {

    private static final Map<Class<?>, Class<?>> WRAPPERS = Map.of(
            boolean.class, Boolean.class,
            byte.class, Byte.class,
            char.class, Character.class,
            short.class, Short.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class);

    private InjectionTypes() {}

    /**
     * Returns the class {@code type} erases to.
     *
     * @param type a class, parameterized type, generic array, type variable or wildcard
     * @return the erasure of {@code type}
     */
    static Class<?> erasure(Type type) {
        return switch (type) {
            case Class<?> raw -> raw;
            case ParameterizedType parameterized -> erasure(parameterized.getRawType());
            case GenericArrayType array ->
                erasure(array.getGenericComponentType()).arrayType();
            case TypeVariable<?> variable -> erasure(variable.getBounds()[0]);
            case WildcardType wildcard -> erasure(wildcard.getUpperBounds()[0]);
            default -> throw new IllegalArgumentException("Unsupported type: " + type.getTypeName());
        };
    }

    /**
     * Returns the class whose instances a value of {@code type} can be: its wrapper for a primitive, itself otherwise.
     *
     * @param type any class
     * @return the wrapper of a primitive {@code type}, or {@code type}
     */
    static Class<?> boxed(Class<?> type) {
        return type.isPrimitive() ? Objects.requireNonNull(WRAPPERS.get(type)) : type;
    }
}
