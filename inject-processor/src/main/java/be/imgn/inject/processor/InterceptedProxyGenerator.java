package be.imgn.inject.processor;

import java.util.Locale;
import java.util.stream.IntStream;
import javax.lang.model.element.Modifier;

import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ArrayTypeName;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.TypeVariableName;

/**
 * Emits the {@code $Bean$$Intercepted} subclass: one static call-site descriptor and one chain field per intercepted
 * method, plus an override that dispatches through the chain to {@code super}.
 *
 * <p>Both the chain field and the joinpoint lambda are instance state, so they are only in place once the bean's own
 * constructor has returned. Each override therefore null-checks its chain and calls {@code super} directly until then:
 * a call the constructor makes to an intercepted method runs unintercepted rather than against a half-built proxy.
 */
final class InterceptedProxyGenerator {

    private static final ClassName CONTEXT = ClassName.get("be.imgn.inject.context", "Context");
    private static final ClassName INTERCEPTED_METHOD = ClassName.get("be.imgn.inject.context", "InterceptedMethod");
    private static final ClassName METHOD_INTERCEPTION = ClassName.get("be.imgn.inject.context", "MethodInterception");
    private static final ClassName JOINPOINT = ClassName.get("be.imgn.inject.context", "Joinpoint");
    private static final ClassName INTERCEPTION_EXCEPTION = ClassName.get("be.imgn.inject", "InterceptionException");
    private static final ClassName LIST = ClassName.get("java.util", "List");
    private static final ClassName OBJECT = ClassName.get("java.lang", "Object");
    private static final ClassName THROWABLE = ClassName.get("java.lang", "Throwable");
    private static final ClassName RUNTIME_EXCEPTION = ClassName.get("java.lang", "RuntimeException");
    private static final ClassName ERROR = ClassName.get("java.lang", "Error");

    private InterceptedProxyGenerator() {}

    /**
     * {@return the proxy source file for {@code model}}
     *
     * @param model the bean model; its {@code interception()} must not be {@code null}
     */
    static JavaFile generate(BeanModel model) {
        InterceptionModel interception = model.interception();
        if (interception == null) {
            throw new IllegalArgumentException("No interception model for " + model.beanType());
        }
        TypeSpec.Builder type = TypeSpec.classBuilder(interception.proxySimpleName())
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .superclass(model.beanType());
        for (InterceptedMethodModel method : interception.methods()) {
            type.addField(descriptor(model, method));
            type.addField(chainField(method));
            if (method.typeVariables().isEmpty()) {
                type.addField(joinpointField(method));
            }
            type.addMethod(override(model, method));
        }
        type.addMethod(constructor(interception));
        return JavaFile.builder(model.packageName(), type.build()).build();
    }

    /** {@return the {@code private static final InterceptedMethod} describing one call site} */
    private static FieldSpec descriptor(BeanModel model, InterceptedMethodModel method) {
        CodeBlock parameterTypes = method.parameters().stream()
                .map(parameter -> CodeBlock.of("$T.class", rawType(parameter.type())))
                .collect(CodeBlock.joining(", "));
        CodeBlock bindings = method.bindings().stream().collect(CodeBlock.joining(", "));
        return FieldSpec.builder(
                        INTERCEPTED_METHOD, descriptorName(method), Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                .initializer(
                        "$T.of($T.class, $S, $L, $T.of($L), $T.of($L))",
                        INTERCEPTED_METHOD,
                        model.beanType(),
                        method.name(),
                        returnTypeLiteral(method.returnType()),
                        LIST,
                        parameterTypes,
                        LIST,
                        bindings)
                .build();
    }

    /** {@return the chain field, assigned once per proxy instance from the constructor} */
    private static FieldSpec chainField(InterceptedMethodModel method) {
        return FieldSpec.builder(METHOD_INTERCEPTION, method.fieldName(), Modifier.PRIVATE, Modifier.FINAL)
                .build();
    }

    /**
     * {@return the hoisted joinpoint lambda: one allocation per proxy instead of one per call}
     *
     * <p>Emitted for every method but a generic one, whose type variables are out of scope in a field initializer;
     * {@link #override} declares that lambda as a local instead.
     */
    private static FieldSpec joinpointField(InterceptedMethodModel method) {
        return FieldSpec.builder(JOINPOINT, joinpointName(method), Modifier.PRIVATE, Modifier.FINAL)
                .initializer(joinpointLambda(method))
                .build();
    }

    /** {@return the lambda calling the bean's own implementation with the arguments unpacked from the array} */
    private static CodeBlock joinpointLambda(InterceptedMethodModel method) {
        return method.returnType().equals(TypeName.VOID)
                ? CodeBlock.of("arguments -> {\n$>super.$L($L);\nreturn null;\n$<}", method.name(), unpack(method))
                : CodeBlock.of("arguments -> super.$L($L)", method.name(), unpack(method));
    }

    /** {@return the arguments of the joinpoint's {@code super} call, unpacked from the argument array} */
    private static CodeBlock unpack(InterceptedMethodModel method) {
        return IntStream.range(0, method.parameters().size())
                .mapToObj(index -> CodeBlock.of(
                        "($T) arguments[$L]", method.parameters().get(index).type(), index))
                .collect(CodeBlock.joining(", "));
    }

    /** {@return the proxy constructor: {@code Context} first, then the bean's own constructor parameters} */
    private static MethodSpec constructor(InterceptionModel interception) {
        MethodSpec.Builder builder =
                MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC).addParameter(CONTEXT, "context");
        for (ProxyParameter parameter : interception.constructorParameters()) {
            builder.addParameter(parameter.type(), parameter.name());
        }
        CodeBlock superArguments = interception.constructorParameters().stream()
                .map(parameter -> CodeBlock.of("$L", parameter.name()))
                .collect(CodeBlock.joining(", "));
        builder.addStatement("super($L)", superArguments);
        for (InterceptedMethodModel method : interception.methods()) {
            builder.addStatement("this.$L = $L.bind(context)", method.fieldName(), descriptorName(method));
        }
        return builder.build();
    }

    /** {@return the override that runs the chain, falling through to {@code super} before the chain is assigned} */
    private static MethodSpec override(BeanModel model, InterceptedMethodModel method) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder(method.name())
                .addAnnotation(Override.class)
                .returns(method.returnType())
                .varargs(method.varargs())
                .addTypeVariables(method.typeVariables());
        Modifier visibility = method.visibility();
        if (visibility != null) {
            builder.addModifiers(visibility);
        }
        for (ProxyParameter parameter : method.parameters()) {
            builder.addParameter(parameter.type(), parameter.name());
        }
        for (TypeName thrown : method.thrownTypes()) {
            builder.addException(thrown);
        }
        if (method.declaresThrowable()) {
            builder.addException(THROWABLE);
        }
        if (method.uncheckedCast()) {
            builder.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "unchecked")
                    .build());
        }
        CodeBlock arguments = method.parameters().stream()
                .map(parameter -> CodeBlock.of("$L", parameter.name()))
                .collect(CodeBlock.joining(", "));
        builder.addStatement("$T $$interception = this.$L", METHOD_INTERCEPTION, method.fieldName());
        builder.beginControlFlow("if ($$interception == null)");
        if (method.returnType().equals(TypeName.VOID)) {
            builder.addStatement("super.$L($L)", method.name(), arguments);
            builder.addStatement("return");
        } else {
            builder.addStatement("return super.$L($L)", method.name(), arguments);
        }
        builder.endControlFlow();
        CodeBlock joinpoint;
        if (method.typeVariables().isEmpty()) {
            joinpoint = CodeBlock.of("this.$L", joinpointName(method));
        } else {
            // A generic method's type variables are out of scope in a field initializer, so its joinpoint is local.
            joinpoint = CodeBlock.of("$$joinpoint");
            builder.addStatement("$T $L = $L", JOINPOINT, joinpoint, joinpointLambda(method));
        }
        builder.beginControlFlow("try");
        CodeBlock call = CodeBlock.of("$$interception.invoke(this, new $T[] {$L}, $L)", OBJECT, arguments, joinpoint);
        if (method.returnType().equals(TypeName.VOID)) {
            builder.addStatement("$L", call);
        } else if (method.returnType().isPrimitive()) {
            builder.addStatement("$T $$result = $L", OBJECT, call);
            builder.beginControlFlow("if ($$result == null)");
            builder.addStatement(
                    "throw new $T($S)",
                    INTERCEPTION_EXCEPTION,
                    "An interceptor returned null for the primitive return type " + method.returnType() + " of "
                            + model.beanType().simpleName() + "." + method.name());
            builder.endControlFlow();
            builder.addStatement("return ($T) $$result", method.returnType().box());
        } else {
            builder.addStatement("return ($T) $L", method.returnType(), call);
        }
        builder.nextControlFlow("catch ($T | $T $$e)", RUNTIME_EXCEPTION, ERROR);
        builder.addStatement("throw $$e");
        for (TypeName thrown : method.thrownTypes()) {
            builder.nextControlFlow("catch ($T $$e)", thrown);
            builder.addStatement("throw $$e");
        }
        builder.nextControlFlow("catch ($T $$t)", THROWABLE);
        if (method.declaresThrowable()) {
            builder.addStatement("throw $$t");
        } else {
            builder.addStatement(
                    "throw new $T($S, $$t)",
                    INTERCEPTION_EXCEPTION,
                    "An interceptor threw an undeclared exception from "
                            + model.beanType().simpleName() + "." + method.name());
        }
        builder.endControlFlow();
        return builder.build();
    }

    private static String descriptorName(InterceptedMethodModel method) {
        return method.fieldName().replace('$', '_').toUpperCase(Locale.ROOT);
    }

    private static String joinpointName(InterceptedMethodModel method) {
        return method.fieldName() + "$joinpoint";
    }

    /** {@return a class-literal-safe type name: type arguments and type variables erased, primitives preserved} */
    private static TypeName rawType(TypeName type) {
        if (type instanceof ParameterizedTypeName parameterized) {
            return parameterized.rawType();
        }
        if (type instanceof TypeVariableName variable) {
            return variable.bounds().isEmpty()
                    ? OBJECT
                    : rawType(variable.bounds().get(0));
        }
        if (type instanceof ArrayTypeName array) {
            return ArrayTypeName.of(rawType(array.componentType()));
        }
        return type;
    }

    /** {@return the class literal expression for a return type, using {@code void.class} for void} */
    private static CodeBlock returnTypeLiteral(TypeName returnType) {
        return returnType.equals(TypeName.VOID)
                ? CodeBlock.of("void.class")
                : CodeBlock.of("$T.class", rawType(returnType));
    }
}
