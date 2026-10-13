package be.imgn.inject.processor;

import be.imgn.gaia.processing.Diagnostics;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.ParameterizedTypeName;
import be.imgn.gaia.processing.source.TypeName;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import org.jspecify.annotations.Nullable;

/** Stage 2: adds default factories for undeclared in-compilation dependencies and rejects dependency cycles. */
final class PlanStage {

    private final ModelFactory models;
    private final ProcessingContext context;
    private final Diagnostics diagnostics;
    // Types bound in this round or an earlier one, by @Provides methods, exposed types or default factories.
    private final Set<TypeName> bound = new HashSet<>();

    PlanStage(ProcessingContext context, ModelFactory models, Diagnostics diagnostics) {
        this.context = context;
        this.models = models;
        this.diagnostics = diagnostics;
    }

    /** {@return {@code true} if the graph is valid; {@code false} if a cycle was reported} */
    boolean plan(Gathered gathered) {
        planDefaultFactories(gathered);
        List<TypeElement> cycle = findCycle(gathered.beans().keySet(), gathered.edges());
        if (cycle != null) {
            String path = cycle.stream()
                    .map(type -> type.getQualifiedName().toString())
                    .collect(Collectors.joining(" -> "));
            diagnostics.on(cycle.get(0)).error("Dependency cycle detected: " + path);
            return false;
        }
        if (!checkNoDirectAssistedInjection(gathered)) {
            return false;
        }
        // Duplicate ordinary bindings that arise within one compilation fall through to the runtime
        // DuplicateBindingException: cross-module duplicates are undetectable at compile time under the
        // ServiceLoader architecture, so runtime detection is the single, consistent safety net. @IntoMap keys
        // are the exception - duplicates visible in this compilation fail fast here, and the runtime check
        // still catches cross-module collisions.
        return checkDuplicateMapKeys(gathered);
    }

    /** {@return {@code true} if no two contributions in this compilation share an {@code @IntoMap} key} */
    private boolean checkDuplicateMapKeys(Gathered gathered) {
        Set<String> seenSlots = new HashSet<>();
        for (Map.Entry<TypeElement, BeanModel> entry : gathered.beans().entrySet()) {
            ContributionModel contribution = entry.getValue().contribution();
            if (contribution == null || contribution.mapKey() == null) {
                continue;
            }
            String slot = slot(contribution);
            if (!seenSlots.add(slot)) {
                diagnostics
                        .on(entry.getKey())
                        .error("Duplicate @IntoMap key \"" + contribution.mapKey() + "\" for "
                                + contribution.elementType() + ".");
                return false;
            }
        }
        for (ProvidesModel provides : gathered.provides()) {
            ContributionModel contribution = provides.contribution();
            if (contribution == null || contribution.mapKey() == null) {
                continue;
            }
            String slot = slot(contribution);
            if (!seenSlots.add(slot)) {
                diagnostics.error("Duplicate @IntoMap key \"" + contribution.mapKey() + "\" for "
                        + contribution.elementType() + ".");
                return false;
            }
        }
        return true;
    }

    /**
     * {@return {@code true} if no edge, {@code @Provides} parameter, or other assisted recipe's container parameter
     * injects an assisted bean directly}
     *
     * <p>{@code Provider<AssistedBean>} sites on a bean's own {@code @Inject} constructor/members never appear in
     * {@link Gathered#edges()} (the Provider-breaks-cycle rule), so they are not caught here; they fail at runtime
     * instead, as a missing binding.
     */
    private boolean checkNoDirectAssistedInjection(Gathered gathered) {
        for (Map.Entry<TypeElement, List<TypeElement>> entry : gathered.edges().entrySet()) {
            for (TypeElement dependency : entry.getValue()) {
                if (gathered.assistedBeanNames()
                        .contains(dependency.getQualifiedName().toString())) {
                    diagnostics
                            .on(entry.getKey())
                            .error(dependency.getSimpleName()
                                    + " has assisted parameters and is only available through an @AssistedFactory.");
                    return false;
                }
            }
        }
        for (AssistedReference reference : gathered.assistedReferences()) {
            if (gathered.assistedBeanNames().contains(reference.typeName())) {
                diagnostics
                        .on(reference.site())
                        .error(simpleName(reference.typeName())
                                + " has assisted parameters and is only available through an @AssistedFactory.");
                return false;
            }
        }
        return true;
    }

    /** {@return the simple-name suffix of a qualified type name} */
    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    /** {@return the seen-slot identity for a contribution: its element type and map key} */
    private static String slot(ContributionModel contribution) {
        return contribution.elementType() + "#" + contribution.mapKey();
    }

    private void planDefaultFactories(Gathered gathered) {
        Map<TypeElement, BeanModel> beans = gathered.beans();
        Map<TypeElement, List<TypeElement>> edges = gathered.edges();
        bound.addAll(suppliedTypes(gathered));
        Deque<TypeElement> queue = new ArrayDeque<>(gathered.seeds());
        Set<TypeElement> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeElement dependency = queue.poll();
            if (!seen.add(dependency)
                    || beans.containsKey(dependency)
                    || gathered.interceptionRejected().contains(dependency)) {
                continue;
            }
            if (!context.isFromSource(dependency)) {
                // Library / cross-module type: satisfied elsewhere, checked at runtime
                continue;
            }
            if (bound.contains(ClassName.of(dependency))) {
                // Bound by a @Provides method or exposed by a bean: a default factory would shadow or duplicate it
                continue;
            }
            if (isAbstract(dependency)) {
                // Not instantiable: an implementation must be bound elsewhere, checked at runtime
                continue;
            }
            if (models.injectConstructor(dependency).isPresent()) {
                // Already an @Inject bean handled above
                continue;
            }
            if (models.hasAccessibleNoArgConstructor(dependency)) {
                bound.add(ClassName.of(dependency));
                beans.put(dependency, models.defaultModel(dependency));
                edges.put(dependency, List.of());
            }
            // Otherwise no factory can build it: it must be bound elsewhere, for instance by Injector.builder().bind,
            // and the injector checks that when it is created
        }
    }

    /** {@return the raw types bound in this round by {@code @Provides} methods and by beans' exposed types} */
    private static Set<TypeName> suppliedTypes(Gathered gathered) {
        Set<TypeName> supplied = new HashSet<>();
        for (ProvidesModel provides : gathered.provides()) {
            if (provides.contribution() == null) {
                supplied.add(raw(provides.producedType()));
                provides.exposedTypes().forEach(type -> supplied.add(raw(type)));
            }
        }
        for (BeanModel bean : gathered.beans().values()) {
            if (bean.contribution() == null) {
                bean.exposedTypes().forEach(type -> supplied.add(raw(type)));
            }
        }
        return supplied;
    }

    private static TypeName raw(TypeName type) {
        return type instanceof ParameterizedTypeName parameterized ? parameterized.rawType() : type;
    }

    private static boolean isAbstract(TypeElement type) {
        return type.getKind() == ElementKind.INTERFACE
                || type.getKind() == ElementKind.ANNOTATION_TYPE
                || type.getModifiers().contains(Modifier.ABSTRACT);
    }

    private static @Nullable List<TypeElement> findCycle(
            Set<TypeElement> nodes, Map<TypeElement, List<TypeElement>> edges) {
        Set<TypeElement> visited = new HashSet<>();
        Deque<TypeElement> stack = new ArrayDeque<>();
        Set<TypeElement> onStack = new HashSet<>();
        for (TypeElement node : nodes) {
            List<TypeElement> cycle = visit(node, nodes, edges, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        return null;
    }

    private static @Nullable List<TypeElement> visit(
            TypeElement node,
            Set<TypeElement> nodes,
            Map<TypeElement, List<TypeElement>> edges,
            Set<TypeElement> visited,
            Deque<TypeElement> stack,
            Set<TypeElement> onStack) {
        if (onStack.contains(node)) {
            List<TypeElement> path = new ArrayList<>();
            boolean started = false;
            for (TypeElement element : stack) {
                started = started || element.equals(node);
                if (started) {
                    path.add(element);
                }
            }
            path.add(node);
            return path;
        }
        if (!visited.add(node)) {
            return null;
        }
        stack.addLast(node);
        onStack.add(node);
        for (TypeElement dependency : edges.getOrDefault(node, List.of())) {
            if (!nodes.contains(dependency)) {
                continue;
            }
            List<TypeElement> cycle = visit(dependency, nodes, edges, visited, stack, onStack);
            if (cycle != null) {
                return cycle;
            }
        }
        stack.removeLast();
        onStack.remove(node);
        return null;
    }
}
