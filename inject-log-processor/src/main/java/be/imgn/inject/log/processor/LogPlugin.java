package be.imgn.inject.log.processor;

import be.imgn.gaia.processing.source.ClassName;
import be.imgn.gaia.processing.source.CodeBlock;
import be.imgn.inject.log.Log;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;
import java.util.Optional;

/**
 * Injects a {@link java.util.logging.Logger} into fields annotated with {@link Log}, named after the declaring type.
 */
public final class LogPlugin implements InjectPlugin {

    private static final ClassName LOGGER = ClassName.of("java.util.logging", "Logger");

    /** Creates the plugin. Instantiated by {@link java.util.ServiceLoader}. */
    public LogPlugin() {}

    @Override
    public Optional<CodeBlock> initializer(InjectionSite site) {
        if (site.element().getAnnotation(Log.class) == null) {
            return Optional.empty();
        }
        return Optional.of(CodeBlock.of("$T.getLogger($T.class.getName())", LOGGER, ClassName.of(site.beanType())));
    }
}
