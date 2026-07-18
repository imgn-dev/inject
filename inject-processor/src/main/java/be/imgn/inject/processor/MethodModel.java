package be.imgn.inject.processor;

import java.util.List;

/**
 * An {@code @Inject} method to generate injection for.
 *
 * @param name the method name
 * @param parameters the method parameters, in order
 */
record MethodModel(String name, List<ParameterModel> parameters) {}
