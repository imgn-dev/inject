/**
 * Module-path example: the framework resolves generated factories declared via {@code provides} directives. The
 * factory names below are exactly those the annotation processor reports in its compile-time note.
 */
module be.imgn.inject.examples.modular {
    requires be.imgn.inject;
    requires static org.jspecify;

    // Keep this list in sync with the factory names the annotation processor prints in its compile-time note
    // (one entry per @Inject/@Provides class). A processor cannot write the consumer's module descriptor.
    provides be.imgn.inject.context.Factory with
            be.imgn.inject.examples.modular.$Widget$$Factory,
            be.imgn.inject.examples.modular.$Service$$Factory,
            be.imgn.inject.examples.modular.$AlphaPlugin$$Factory,
            be.imgn.inject.examples.modular.$BetaPlugin$$Factory,
            be.imgn.inject.examples.modular.$PluginHost$$Factory;
}
