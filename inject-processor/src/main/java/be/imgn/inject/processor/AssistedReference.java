package be.imgn.inject.processor;

import javax.lang.model.element.Element;

/**
 * A container-resolved parameter site whose declared type may name an assisted bean: a {@code @Provides} method
 * parameter, or another assisted recipe's non-{@code @Assisted} (container) parameter. Recorded by the gather stage so
 * the plan stage can reject a direct reference to an assisted bean type at any of these sites, alongside ordinary
 * {@code @Inject} edges.
 *
 * @param site the parameter's declaring executable, reported as the diagnostic location
 * @param typeName the referenced parameter's qualified type name (Provider-unwrapped)
 */
record AssistedReference(Element site, String typeName) {}
