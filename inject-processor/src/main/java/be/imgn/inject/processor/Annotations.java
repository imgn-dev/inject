package be.imgn.inject.processor;

import java.util.ArrayList;
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

import be.imgn.inject.ConditioningAnnotation;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.QualifyingAnnotation;
import be.imgn.inject.ScopingAnnotation;

/** Reads framework-related annotations off elements and renders them as generated expressions. */
final class Annotations {

    private static final ClassName NAMED = ClassName.get("be.imgn.inject", "Named");
    private static final ClassName NAMES = ClassName.get("be.imgn.inject", "Names");
    private static final ClassName ANNOTATION_METADATA = ClassName.get("be.imgn.inject.context", "AnnotationMetadata");
    private static final ClassName ANNOTATION_VALUE = ClassName.get("be.imgn.inject.context", "AnnotationValue");
    private static final ClassName QUALIFYING_ANNOTATION = ClassName.get("be.imgn.inject", "QualifyingAnnotation");
    private static final ClassName SCOPING_ANNOTATION = ClassName.get("be.imgn.inject", "ScopingAnnotation");
    private static final ClassName INJECTING_ANNOTATION = ClassName.get("be.imgn.inject", "InjectingAnnotation");
    private static final ClassName CONDITIONING_ANNOTATION = ClassName.get("be.imgn.inject", "ConditioningAnnotation");
    private static final ClassName MAP = ClassName.get("java.util", "Map");
    private static final ClassName SET = ClassName.get("java.util", "Set");
    private static final ClassName LIST = ClassName.get("java.util", "List");

    private Annotations() {}

    /**
     * {@return the rendered qualifier for the element's qualifying annotation, if any}
     *
     * <p>When the qualifier is an attributed custom annotation, an {@link AnnotationImplRequest} for it is added to
     * {@code implRequests} so the caller can emit a matching implementation into {@code targetPackage}.
     */
    static Optional<RenderedQualifier> qualifier(
            Element element, Elements elements, String targetPackage, Set<AnnotationImplRequest> implRequests) {
        for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
            TypeElement annotationType =
                    (TypeElement) mirror.getAnnotationType().asElement();
            if (annotationType.getAnnotation(QualifyingAnnotation.class) != null) {
                return Optional.of(render(annotationType, mirror, elements, targetPackage, implRequests));
            }
        }
        return Optional.empty();
    }

    /**
     * {@return an {@code AnnotationMetadata} expression holding every framework-related annotation on the elements}
     *
     * <p>A framework-related annotation is one meta-annotated with {@link InjectingAnnotation},
     * {@link QualifyingAnnotation}, or {@link ScopingAnnotation}. Each is rendered as {@code AnnotationValue} data —
     * type, member values, and the stereotypes its type carries — so no implementation class is needed to read it.
     * Later elements win for a repeated annotation type, so callers pass enclosing elements first and the site last.
     */
    static CodeBlock metadata(List<? extends Element> elements, Elements utils) {
        Map<String, CodeBlock> byType = new LinkedHashMap<>();
        for (Element element : elements) {
            for (AnnotationMirror mirror : element.getAnnotationMirrors()) {
                TypeElement annotationType =
                        (TypeElement) mirror.getAnnotationType().asElement();
                if (!isFrameworkRelated(annotationType)) {
                    continue;
                }
                byType.put(
                        annotationType.getQualifiedName().toString(), annotationValue(annotationType, mirror, utils));
            }
        }
        if (byType.isEmpty()) {
            return CodeBlock.of("$T.empty()", ANNOTATION_METADATA);
        }
        return CodeBlock.of(
                "$T.of($L)", ANNOTATION_METADATA, byType.values().stream().collect(CodeBlock.joining(", ")));
    }

    private static boolean isFrameworkRelated(TypeElement annotationType) {
        return annotationType.getAnnotation(QualifyingAnnotation.class) != null
                || annotationType.getAnnotation(ScopingAnnotation.class) != null
                || annotationType.getAnnotation(InjectingAnnotation.class) != null
                || annotationType.getAnnotation(ConditioningAnnotation.class) != null;
    }

    /** {@return an {@code AnnotationValue.of(...)} expression describing the annotation} */
    private static CodeBlock annotationValue(TypeElement annotationType, AnnotationMirror mirror, Elements utils) {
        Map<String, AnnotationValue> values = valuesByName(mirror, utils);
        CodeBlock members = values.entrySet().stream()
                .map(entry -> CodeBlock.of("$T.entry($S, $L)", MAP, entry.getKey(), metadataValue(entry.getValue())))
                .collect(CodeBlock.joining(", "));
        CodeBlock memberMap =
                members.isEmpty() ? CodeBlock.of("$T.of()", MAP) : CodeBlock.of("$T.ofEntries($L)", MAP, members);
        return CodeBlock.of(
                "$T.of($T.class, $L, $L)",
                ANNOTATION_VALUE,
                ClassName.get(annotationType),
                memberMap,
                stereotypes(annotationType));
    }

    private static CodeBlock stereotypes(TypeElement annotationType) {
        List<CodeBlock> present = new ArrayList<>();
        if (annotationType.getAnnotation(QualifyingAnnotation.class) != null) {
            present.add(CodeBlock.of("$T.class", QUALIFYING_ANNOTATION));
        }
        if (annotationType.getAnnotation(ScopingAnnotation.class) != null) {
            present.add(CodeBlock.of("$T.class", SCOPING_ANNOTATION));
        }
        if (annotationType.getAnnotation(InjectingAnnotation.class) != null) {
            present.add(CodeBlock.of("$T.class", INJECTING_ANNOTATION));
        }
        if (annotationType.getAnnotation(ConditioningAnnotation.class) != null) {
            present.add(CodeBlock.of("$T.class", CONDITIONING_ANNOTATION));
        }
        return CodeBlock.of("$T.of($L)", SET, present.stream().collect(CodeBlock.joining(", ")));
    }

    /** {@return a value expression for metadata, rendering array members as {@code List.of(...)}} */
    private static CodeBlock metadataValue(AnnotationValue annotationValue) {
        Object raw = annotationValue.getValue();
        if (raw instanceof List<?> elements) {
            CodeBlock items = elements.stream()
                    .map(element -> metadataValue((AnnotationValue) element))
                    .collect(CodeBlock.joining(", "));
            return CodeBlock.of("$T.of($L)", LIST, items);
        }
        if (raw instanceof AnnotationMirror) {
            // Nested annotations carry no binding or inspection meaning here; record the type only.
            return CodeBlock.of("$T.class", ClassName.get((TypeElement)
                    ((AnnotationMirror) raw).getAnnotationType().asElement()));
        }
        return value(annotationValue);
    }

    /** {@return an annotation-instance expression, generating an implementation class where one is needed} */
    private static CodeBlock instance(
            TypeElement annotationType,
            AnnotationMirror mirror,
            Elements utils,
            String targetPackage,
            Set<AnnotationImplRequest> implRequests) {
        List<ExecutableElement> members = ElementFilter.methodsIn(annotationType.getEnclosedElements());
        Map<String, AnnotationValue> values = valuesByName(mirror, utils);
        if (annotationType.getQualifiedName().contentEquals(NAMED.canonicalName())) {
            return CodeBlock.of("$T.named($L)", NAMES, value(Objects.requireNonNull(values.get("value"))));
        }
        implRequests.add(new AnnotationImplRequest(annotationType, targetPackage));
        CodeBlock arguments = members.stream()
                .map(member -> value(
                        Objects.requireNonNull(values.get(member.getSimpleName().toString()))))
                .collect(CodeBlock.joining(", "));
        return CodeBlock.of(
                "new $T($L)", AnnotationImplGenerator.implClassName(annotationType, targetPackage), arguments);
    }

    private static Map<String, AnnotationValue> valuesByName(AnnotationMirror mirror, Elements utils) {
        Map<String, AnnotationValue> values = new LinkedHashMap<>();
        utils.getElementValuesWithDefaults(mirror)
                .forEach((member, value) -> values.put(member.getSimpleName().toString(), value));
        return values;
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
            TypeElement annotationType,
            AnnotationMirror mirror,
            Elements elements,
            String targetPackage,
            Set<AnnotationImplRequest> implRequests) {
        if (ElementFilter.methodsIn(annotationType.getEnclosedElements()).isEmpty()) {
            return new RenderedQualifier(CodeBlock.of("$T.class", ClassName.get(annotationType)), true);
        }
        return new RenderedQualifier(instance(annotationType, mirror, elements, targetPackage, implRequests), false);
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
