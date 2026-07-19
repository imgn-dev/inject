package be.imgn.inject.processor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.element.TypeElement;

import org.jspecify.annotations.Nullable;

/** Stage 2: adds default factories for undeclared in-compilation dependencies and rejects dependency cycles. */
final class PlanStage {

    private final ModelFactory models;
    private final Diagnostics diagnostics;

    PlanStage(ModelFactory models, Diagnostics diagnostics) {
        this.models = models;
        this.diagnostics = diagnostics;
    }

    /** {@return {@code true} if the graph is valid; {@code false} if a cycle was reported} */
    boolean plan(Gathered gathered, RoundEnvironment roundEnv) {
        planDefaultFactories(gathered, roundEnv);
        List<TypeElement> cycle = findCycle(gathered.beans().keySet(), gathered.edges());
        if (cycle != null) {
            String path = cycle.stream()
                    .map(type -> type.getQualifiedName().toString())
                    .collect(Collectors.joining(" -> "));
            diagnostics.error("Dependency cycle detected: " + path, cycle.get(0));
            return false;
        }
        // Duplicate bindings that arise within one compilation fall through to the runtime DuplicateBindingException.
        // Cross-module duplicates are undetectable at compile time under the ServiceLoader architecture, so runtime
        // detection is the single, consistent safety net.
        return true;
    }

    private void planDefaultFactories(Gathered gathered, RoundEnvironment roundEnv) {
        Map<TypeElement, BeanModel> beans = gathered.beans();
        Map<TypeElement, List<TypeElement>> edges = gathered.edges();
        Deque<TypeElement> queue = new ArrayDeque<>(gathered.seeds());
        Set<TypeElement> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            TypeElement dependency = queue.poll();
            if (!seen.add(dependency) || beans.containsKey(dependency)) {
                continue;
            }
            if (!roundEnv.getRootElements().contains(dependency)) {
                continue; // library / cross-module type: satisfied elsewhere, checked at runtime
            }
            if (models.injectConstructor(dependency).isPresent()) {
                continue; // already an @Inject bean handled above
            }
            if (models.hasAccessibleNoArgConstructor(dependency)) {
                beans.put(dependency, models.defaultModel(dependency));
                edges.put(dependency, List.of());
            } else {
                diagnostics.error(
                        "Cannot satisfy dependency " + dependency.getQualifiedName()
                                + ": it has no @Inject constructor and no accessible no-argument constructor."
                                + " Annotate it with @Inject or provide it with @Provides.",
                        dependency);
            }
        }
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
