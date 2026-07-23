package be.imgn.inject.processor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Types;

import be.imgn.inject.Assisted;
import be.imgn.inject.AssistedFactory;

/** Resolves an {@code @AssistedFactory} method to its recipe and validates the assisted-parameter bijection. */
final class AssistedMatcher {

    private final Types types;
    private final Diagnostics diagnostics;

    AssistedMatcher(Types types, Diagnostics diagnostics) {
        this.types = types;
        this.diagnostics = diagnostics;
    }

    /**
     * The implementation class and recipe-map key a factory method links to, before the recipe lookup.
     *
     * @param implementationElement the class implementing the recipe
     * @param key the {@code Gathered.assistedRecipes()} lookup key
     * @param recipeMethod the linked {@code @Provides} method name, or empty for a constructor recipe
     */
    record Link(TypeElement implementationElement, String key, String recipeMethod) {}

    /** {@return the method's implementation link, or empty when malformed ({@link #resolve} reports the error)} */
    static Optional<Link> link(ExecutableElement method, AssistedFactory annotation) {
        TypeMirror linked = annotationClassValue(annotation);
        boolean hasValue = !(linked.toString().equals("void"));
        String recipeMethod = annotation.method();
        if (!recipeMethod.isEmpty() && !hasValue) {
            return Optional.empty();
        }
        TypeMirror implementation = hasValue ? linked : method.getReturnType();
        if (!(implementation instanceof DeclaredType declared
                && declared.asElement() instanceof TypeElement implementationElement)) {
            return Optional.empty();
        }
        String key = recipeMethod.isEmpty()
                ? implementationElement.getQualifiedName().toString()
                : implementationElement.getQualifiedName() + "#" + recipeMethod;
        return Optional.of(new Link(implementationElement, key, recipeMethod));
    }

    /** {@return the resolved method, or empty after reporting the failure} */
    Optional<AssistedFactoryMethod> resolve(
            ExecutableElement method, AssistedFactory annotation, Map<String, List<AssistedRecipe>> recipes) {
        Optional<Link> link = link(method, annotation);
        if (link.isEmpty()) {
            if (!annotation.method().isEmpty()
                    && annotationClassValue(annotation).toString().equals("void")) {
                diagnostics.error("@AssistedFactory(method=...) requires the declaring class as value.", method);
            } else {
                diagnostics.error("The @AssistedFactory implementation type is not a class.", method);
            }
            return Optional.empty();
        }
        TypeElement implementationElement = link.get().implementationElement();
        String recipeMethod = link.get().recipeMethod();
        List<AssistedRecipe> candidates = recipes.getOrDefault(link.get().key(), List.of());
        if (candidates.isEmpty()) {
            diagnostics.error(
                    recipeMethod.isEmpty()
                            ? implementationElement.getSimpleName() + " has no assisted constructor."
                            : "There is no assisted @Provides method \"" + recipeMethod + "\" on "
                                    + implementationElement.getSimpleName() + ".",
                    method);
            return Optional.empty();
        }
        if (candidates.size() > 1) {
            diagnostics.error(
                    "The assisted @Provides method \"" + recipeMethod + "\" is ambiguous (overloaded).", method);
            return Optional.empty();
        }
        AssistedRecipe recipe = candidates.get(0);
        TypeMirror produced = recipe.providesMethod() == null
                ? recipe.owner().asType()
                : recipe.executable().getReturnType();
        if (!types.isAssignable(types.erasure(produced), types.erasure(method.getReturnType()))) {
            diagnostics.error(
                    "The recipe's type " + produced + " is not assignable to the factory method's return type.",
                    method);
            return Optional.empty();
        }
        return match(method, recipe);
    }

    private Optional<AssistedFactoryMethod> match(ExecutableElement method, AssistedRecipe recipe) {
        Map<String, Integer> factorySlots = new LinkedHashMap<>();
        List<? extends VariableElement> factoryParameters = method.getParameters();
        for (int index = 0; index < factoryParameters.size(); index++) {
            VariableElement parameter = factoryParameters.get(index);
            if (factorySlots.putIfAbsent(slot(parameter), index) != null) {
                diagnostics.error(
                        "Two factory parameters share type and tag; tag them with @Assisted(\"...\").", parameter);
                return Optional.empty();
            }
        }
        List<Integer> order = new ArrayList<>();
        for (VariableElement parameter : recipe.executable().getParameters()) {
            if (parameter.getAnnotation(Assisted.class) == null) {
                continue;
            }
            Integer index = factorySlots.remove(slot(parameter));
            if (index == null) {
                diagnostics.error(
                        "The factory method does not match the recipe: missing assisted parameter " + slot(parameter)
                                + ".",
                        method);
                return Optional.empty();
            }
            order.add(index);
        }
        if (!factorySlots.isEmpty()) {
            diagnostics.error(
                    "The factory method does not match the recipe: extra parameter(s) "
                            + String.join(", ", factorySlots.keySet()) + ".",
                    method);
            return Optional.empty();
        }
        return Optional.of(new AssistedFactoryMethod(method, recipe, List.copyOf(order)));
    }

    private String slot(VariableElement parameter) {
        Assisted assisted = parameter.getAnnotation(Assisted.class);
        String tag = assisted == null ? "" : assisted.value();
        return types.erasure(parameter.asType()) + "#" + tag;
    }

    private static TypeMirror annotationClassValue(AssistedFactory annotation) {
        try {
            annotation.value();
            throw new IllegalStateException("annotation Class member did not throw");
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }
}
