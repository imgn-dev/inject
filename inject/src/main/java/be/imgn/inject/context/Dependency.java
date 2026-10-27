package be.imgn.inject.context;

import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.Key;
import java.util.ArrayList;
import java.util.List;

/**
 * One dependency of a bean: an injection point's key, except those a processor plugin supplies.
 *
 * @param site the injection point, as error messages name it
 * @param key the dependency's key
 * @param isProvider whether the bean receives a {@code Provider} of it
 */
record Dependency(String site, Key<?> key, boolean isProvider) {

    /** {@return the dependencies of {@code bean}, in injection-point order} */
    static List<Dependency> of(BeanDefinition bean) {
        List<Dependency> dependencies = new ArrayList<>();
        for (InjectionPoint point : bean.injectionPoints()) {
            switch (point) {
                case ConstructorInjectionPoint constructor ->
                    constructor
                            .parameters()
                            .forEach(parameter ->
                                    add(dependencies, "constructor parameter " + parameter.name(), parameter));
                case MethodInjectionPoint method ->
                    method.parameters()
                            .forEach(parameter -> add(
                                    dependencies,
                                    "parameter " + parameter.name() + " of method " + method.methodName(),
                                    parameter));
                case FieldInjectionPoint field -> {
                    if (!field.annotationMetadata().hasStereotype(InjectingAnnotation.class)) {
                        dependencies.add(new Dependency("field " + field.fieldName(), field.key(), field.isProvider()));
                    }
                }
            }
        }
        return dependencies;
    }

    private static void add(List<Dependency> dependencies, String site, Parameter parameter) {
        if (!parameter.annotationMetadata().hasStereotype(InjectingAnnotation.class)) {
            dependencies.add(new Dependency(site, parameter.key(), parameter.isProvider()));
        }
    }
}
