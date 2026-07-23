package be.imgn.inject.processor;

import java.util.List;
import javax.lang.model.element.TypeElement;

/**
 * A user factory interface to implement.
 *
 * @param factoryInterface the annotated interface
 * @param methods its resolved factory methods
 */
record AssistedFactoryModel(TypeElement factoryInterface, List<AssistedFactoryMethod> methods) {}
