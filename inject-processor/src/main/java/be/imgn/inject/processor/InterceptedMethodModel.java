package be.imgn.inject.processor;

import java.util.List;
import javax.lang.model.element.Modifier;

import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeVariableName;

import org.jspecify.annotations.Nullable;

/**
 * One intercepted method to override in the generated proxy.
 *
 * @param name the method name
 * @param fieldName the proxy's chain field name, unique across overloads ({@code name + "$" + index})
 * @param visibility the source method's visibility, mirrored by the override; {@code null} for package-private
 * @param returnType the declared return type; {@code TypeName.VOID} for a void method
 * @param parameters the declared parameters, in order
 * @param thrownTypes the declared checked exception types eligible for their own catch clause, in declaration order,
 *     with unchecked types and duplicates within a hierarchy branch removed
 * @param declaresThrowable whether the method declares {@code Throwable}, so nothing needs wrapping
 * @param varargs whether the last parameter is a varargs parameter
 * @param uncheckedCast whether casting the chain's result needs {@code @SuppressWarnings("unchecked")}
 * @param parametersUncheckedCast whether casting the joinpoint's unpacked arguments needs
 *     {@code @SuppressWarnings("unchecked")}
 * @param typeVariables the method's own type variables, in order
 * @param bindings {@code AnnotationValue.of(...)} expressions for the intercepting annotations, outermost first
 */
record InterceptedMethodModel(
        String name,
        String fieldName,
        @Nullable Modifier visibility,
        TypeName returnType,
        List<ProxyParameter> parameters,
        List<TypeName> thrownTypes,
        boolean declaresThrowable,
        boolean varargs,
        boolean uncheckedCast,
        boolean parametersUncheckedCast,
        List<TypeVariableName> typeVariables,
        List<CodeBlock> bindings) {}
