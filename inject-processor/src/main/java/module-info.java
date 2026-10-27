/**
 * The annotation processor that generates the factories {@code be.imgn.inject} resolves at runtime. Run it from the
 * processor path or the processor module path; plugins implement {@link be.imgn.inject.processor.spi.InjectPlugin}.
 */
module be.imgn.inject.processor {
    requires be.imgn.inject;
    requires transitive be.imgn.gaia.processing;
    requires static org.jspecify;

    exports be.imgn.inject.processor.spi;

    uses be.imgn.inject.processor.spi.InjectPlugin;

    provides javax.annotation.processing.Processor with
            be.imgn.inject.processor.InjectProcessor;
}
