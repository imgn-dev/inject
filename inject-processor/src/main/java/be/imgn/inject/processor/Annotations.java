package be.imgn.inject.processor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.TypeName;

import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.ScopingAnnotation;

/** Reads qualifying and scoping annotations off elements and renders them as generated expressions. */
final class Annotations {

    private static final ClassName NAMED = ClassName.get("be.imgn.inject", "Named");
    private static final ClassName NAMES = ClassName.get("be.imgn.inject", "Names");

    private Annotations() {}

    /**
     * {@return the rendered qualifier for the element's qualifying annotation, if any}
     *
     * <p>When the qualifier is an attributed custom annotation, its {@link TypeElement} is added to {@code implTypes}
     * so the caller can emit a matching {@link QualifierImplGenerator} implementation.
     */
    static Optional<RenderedQualifier> qualifier(Element element, Elements elements, Set<TypeElement> implTypes) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(QualifyingAnnotation.class) != null) {
                return Optional.of(render(annotationType, mirror, elements, implTypes));
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

    private static RenderedQualifier render(
            TypeElement annotationType, AnnotationMirror mirror, Elements elements, Set<TypeElement> implTypes) {
        List<ExecutableElement> members = ElementFilter.methodsIn(annotationType.getEnclosedElements());
        if (members.isEmpty()) {
            return new RenderedQualifier(CodeBlock.of("$T.class", ClassName.get(annotationType)), true);
        }

        Map<String, AnnotationValue> values = new LinkedHashMap<>();
        elements.getElementValuesWithDefaults(mirror)
                .forEach((member, value) -> values.put(member.getSimpleName().toString(), value));

        if (annotationType.getQualifiedName().contentEquals(NAMED.canonicalName())) {
            return new RenderedQualifier(
                    CodeBlock.of("$T.named($L)", NAMES, value(Objects.requireNonNull(values.get("value")))), false);
        }

        implTypes.add(annotationType);
        String packageName =
                elements.getPackageOf(annotationType).getQualifiedName().toString();
        CodeBlock arguments = members.stream()
                .map(member -> value(
                        Objects.requireNonNull(values.get(member.getSimpleName().toString()))))
                .collect(CodeBlock.joining(", "));
        return new RenderedQualifier(
                CodeBlock.of(
                        "new $T($L)", QualifierImplGenerator.implClassName(annotationType, packageName), arguments),
                false);
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
