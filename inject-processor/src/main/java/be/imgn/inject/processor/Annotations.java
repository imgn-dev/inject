package be.imgn.inject.processor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.ScopingAnnotation;

/** Reads qualifying and scoping annotations off elements and renders them as generated expressions. */
final class Annotations {

    private static final ClassName QUALIFIER = ClassName.get("be.imgn.inject", "Qualifier");
    private static final ClassName MAP = ClassName.get("java.util", "Map");

    private Annotations() {}

    /** {@return a {@code new Qualifier(...)} expression for the element's qualifying annotation, if any} */
    static Optional<CodeBlock> qualifier(Element element, Elements elements) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(QualifyingAnnotation.class) != null) {
                return Optional.of(qualifierExpression(annotationType, mirror, elements));
            }
        }
        return Optional.empty();
    }

    /** {@return the element's scoping annotation type, if any} */
    static Optional<ClassName> scope(Element element) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(ScopingAnnotation.class) != null) {
                return Optional.of(ClassName.get(annotationType));
            }
        }
        return Optional.empty();
    }

    private static CodeBlock qualifierExpression(
            TypeElement annotationType, AnnotationMirror mirror, Elements elements) {
        Map<? extends ExecutableElement, ? extends AnnotationValue> values =
                elements.getElementValuesWithDefaults(mirror);
        CodeBlock attributes = values.entrySet().stream()
                .map(entry ->
                        CodeBlock.of("$S, $L", entry.getKey().getSimpleName().toString(), value(entry.getValue())))
                .collect(CodeBlock.joining(", "));
        if (attributes.isEmpty()) {
            return CodeBlock.of("new $T($T.class, $T.of())", QUALIFIER, ClassName.get(annotationType), MAP);
        }
        return CodeBlock.of("new $T($T.class, $T.of($L))", QUALIFIER, ClassName.get(annotationType), MAP, attributes);
    }

    private static CodeBlock value(AnnotationValue annotationValue) {
        Object raw = annotationValue.getValue();
        if (raw instanceof String string) {
            return CodeBlock.of("$S", string);
        }
        if (raw instanceof TypeMirror type) {
            return CodeBlock.of("$T.class", TypeName.get(type));
        }
        if (raw instanceof VariableElement enumConstant) {
            return CodeBlock.of(
                    "$T.$L",
                    ClassName.get((TypeElement) enumConstant.getEnclosingElement()),
                    enumConstant.getSimpleName().toString());
        }
        if (raw instanceof Character character) {
            return CodeBlock.of("'$L'", character);
        }
        if (raw instanceof Long longValue) {
            return CodeBlock.of("$LL", longValue);
        }
        if (raw instanceof Float floatValue) {
            return CodeBlock.of("$Lf", floatValue);
        }
        if (raw instanceof List<?> || raw instanceof AnnotationMirror) {
            throw new IllegalArgumentException(
                    "Array- and annotation-valued qualifier attributes are not supported: " + raw);
        }
        return CodeBlock.of("$L", raw);
    }
}
