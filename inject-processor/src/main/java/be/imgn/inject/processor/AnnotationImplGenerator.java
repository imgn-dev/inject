package be.imgn.inject.processor;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import com.palantir.javapoet.WildcardTypeName;
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

    private static final ClassName ANNOTATION = ClassName.get("java.lang.annotation", "Annotation");
    private static final ClassName CLASS = ClassName.get("java.lang", "Class");
    private static final ClassName OBJECTS = ClassName.get("java.util", "Objects");

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
        return ClassName.get(targetPackage, implSimpleName(annotationType));
    }

    static JavaFile generate(TypeElement annotationType, String targetPackage) {
        ClassName annotationName = ClassName.get(annotationType);
        List<ExecutableElement> members = ElementFilter.methodsIn(annotationType.getEnclosedElements());

        TypeSpec.Builder type = TypeSpec.classBuilder(implSimpleName(annotationType))
                .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
                .addSuperinterface(annotationName);

        MethodSpec.Builder constructor = MethodSpec.constructorBuilder().addModifiers(Modifier.PUBLIC);
        for (ExecutableElement member : members) {
            String name = member.getSimpleName().toString();
            TypeName memberType = TypeName.get(member.getReturnType());
            type.addField(FieldSpec.builder(memberType, name, Modifier.PRIVATE, Modifier.FINAL)
                    .build());
            constructor.addParameter(memberType, name).addStatement("this.$L = $L", name, name);
            type.addMethod(MethodSpec.methodBuilder(name)
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

        return JavaFile.builder(targetPackage, type.build()).build();
    }

    private static MethodSpec annotationTypeMethod(ClassName annotationName) {
        return MethodSpec.methodBuilder("annotationType")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ParameterizedTypeName.get(CLASS, WildcardTypeName.subtypeOf(ANNOTATION)))
                .addStatement("return $T.class", annotationName)
                .build();
    }

    private static MethodSpec equals(ClassName annotationName, List<ExecutableElement> members) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("equals")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.BOOLEAN)
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

    private static MethodSpec hashCode(List<ExecutableElement> members) {
        MethodSpec.Builder builder = MethodSpec.methodBuilder("hashCode")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(TypeName.INT);
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

    private static MethodSpec toString(TypeElement annotationType, List<ExecutableElement> members) {
        ClassName annotationName = ClassName.get(annotationType);
        CodeBlock body = members.stream()
                .map(member -> {
                    String name = member.getSimpleName().toString();
                    return CodeBlock.of("$S + $L", name + "=", name);
                })
                .collect(CodeBlock.joining(" + \", \" + "));
        MethodSpec.Builder builder = MethodSpec.methodBuilder("toString")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .returns(ClassName.get(String.class));
        if (members.isEmpty()) {
            return builder.addStatement("return $S", "@" + Objects.toString(annotationType.getQualifiedName()))
                    .build();
        }
        return builder.addStatement("return $S + ($L) + $S", "@" + annotationName.reflectionName() + "(", body, ")")
                .build();
    }
}
