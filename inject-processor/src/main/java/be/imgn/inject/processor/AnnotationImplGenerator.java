package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.gaia.processing.source.Field;
import be.imgn.gaia.processing.source.JavaFile;
import be.imgn.gaia.processing.source.Method;
import be.imgn.gaia.processing.source.ParameterizedTypeName;
import be.imgn.gaia.processing.source.PrimitiveTypeName;
import be.imgn.gaia.processing.source.Type;
import be.imgn.gaia.processing.source.TypeName;
import be.imgn.gaia.processing.source.WildcardTypeName;
import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Objects;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;

/**
 * Emits a concrete implementation of an attributed qualifier annotation so it can be used as a value-based binding key
 * without reflection. The generated class honours the {@link Annotation} equality and hash-code contract, so two
 * instances with the same attribute values compare equal regardless of the implementation class.
 */
final class AnnotationImplGenerator {

    private static final ClassName ANNOTATION = ClassName.of("java.lang.annotation", "Annotation");
    private static final ClassName CLASS = ClassName.of("java.lang", "Class");
    private static final ClassName OBJECTS = ClassName.of("java.util", "Objects");

    private AnnotationImplGenerator() {}

    /**
     * {@return the simple name of the generated implementation for {@code annotationType}}
     *
     * <p>The annotation's qualified name is mangled into the simple name so implementations for same-named annotations
     * from different packages can coexist in one target package.
     */
    static String implSimpleName(TypeElement annotationType) {
        return "$" + annotationType.getQualifiedName().toString().replace('.', '$') + "$$Impl";
    }

    /**
     * {@return the name of the generated implementation, emitted into {@code targetPackage}}
     *
     * <p>Implementations are emitted into the package of the factory that uses them, never the annotation's own
     * package: the annotation may live in another module, and generating into it would split that package.
     */
    static ClassName implClassName(TypeElement annotationType, String targetPackage) {
        return ClassName.of(targetPackage, implSimpleName(annotationType));
    }

    static JavaFile generate(TypeElement annotationType, String targetPackage) {
        ClassName annotationName = ClassName.of(annotationType);
        List<ExecutableElement> members = ElementFilter.methodsIn(annotationType.getEnclosedElements());

        Type.Builder type = Type.newClassBuilder(implSimpleName(annotationType))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(annotationName);

        Method.Builder constructor = Method.newConstructorBuilder().addModifiers(Modifier.PUBLIC);
        for (ExecutableElement member : members) {
            String name = member.getSimpleName().toString();
            TypeName memberType = TypeName.of(member.getReturnType());
            type.addField(Field.newBuilder(memberType, name, Modifier.PRIVATE, Modifier.FINAL)
                    .build());
            constructor.addParameter(memberType, name).addStatement("this.$L = $L", name, name);
            type.addMethod(Method.newBuilder(name)
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(memberType)
                    .addStatement("return $L", name)
                    .build());
        }

        type.addMethod(constructor.build())
                .addMethod(annotationTypeMethod(annotationName))
                .addMethod(equals(annotationName, members))
                .addMethod(hashCode(members))
                .addMethod(toString(annotationType, members));

        return JavaFile.newBuilder(targetPackage, type.build()).build();
    }

    private static Method annotationTypeMethod(ClassName annotationName) {
        return Method.newBuilder("annotationType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.of(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)))
                .addStatement("return $T.class", annotationName)
                .build();
    }

    private static Method equals(ClassName annotationName, List<ExecutableElement> members) {
        Method.Builder builder = Method.newBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(PrimitiveTypeName.BOOLEAN)
                .addParameter(Object.class, "o");
        if (members.isEmpty()) {
            return builder.addStatement("return o instanceof $T", annotationName)
                    .build();
        }
        CodeBlock comparisons = members.stream()
                .map(member -> {
                    String name = member.getSimpleName().toString();
                    return CodeBlock.of("$T.equals($L, other.$L())", OBJECTS, name, name);
                })
                .collect(CodeBlock.joining("\n&& "));
        return builder.beginControlFlow("if (!(o instanceof $T other))", annotationName)
                .addStatement("return false")
                .endControlFlow()
                .addStatement("return $L", comparisons)
                .build();
    }

    private static Method hashCode(List<ExecutableElement> members) {
        Method.Builder builder = Method.newBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(PrimitiveTypeName.INT);
        if (members.isEmpty()) {
            return builder.addStatement("return 0").build();
        }
        CodeBlock terms = members.stream()
                .map(member -> {
                    String name = member.getSimpleName().toString();
                    return CodeBlock.of("((127 * $S.hashCode()) ^ $T.hashCode($L))", name, OBJECTS, name);
                })
                .collect(CodeBlock.joining("\n+ "));
        return builder.addStatement("return $L", terms).build();
    }

    private static Method toString(TypeElement annotationType, List<ExecutableElement> members) {
        ClassName annotationName = ClassName.of(annotationType);
        CodeBlock body = members.stream()
                .map(member -> {
                    String name = member.getSimpleName().toString();
                    return CodeBlock.of("$S + $L", name + "=", name);
                })
                .collect(CodeBlock.joining(" + \", \" + "));
        Method.Builder builder = Method.newBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.of(String.class));
        if (members.isEmpty()) {
            return builder.addStatement("return $S", "@" + Objects.toString(annotationType.getQualifiedName()))
                    .build();
        }
        return builder.addStatement("return $S + ($L) + $S", "@" + annotationName.reflectionName() + "(", body, ")")
                .build();
    }
}
