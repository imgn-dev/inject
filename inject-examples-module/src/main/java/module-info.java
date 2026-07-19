/**
 * Module-path example: the framework resolves generated factories declared via {@code provides} directives. The
 * factory names below are exactly those the annotation processor reports in its compile-time note.
 */
module be.imgn.inject.examples.modular {
    requires be.imgn.inject;
    requires static org.jspecify;

    provides be.imgn.inject.context.Factory with
            be.imgn.inject.examples.modular.$Widget$$Factory,
            be.imgn.inject.examples.modular.$Service$$Factory;
}
