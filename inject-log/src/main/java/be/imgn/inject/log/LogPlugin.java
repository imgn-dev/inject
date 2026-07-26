package be.imgn.inject.log;

import java.util.Optional;

import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;

import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;

/**
 * Injects a {@link java.util.logging.Logger} into fields annotated with {@link Log}, named after the declaring type.
 */
public final class LogPlugin implements InjectPlugin {

    private static final ClassName LOGGER = ClassName.get("java.util.logging", "Logger");

    /** Creates the plugin. Instantiated by {@link java.util.ServiceLoader}. */
    public LogPlugin() {}

    @Override
    public Optional<CodeBlock> initializer(InjectionSite site) {
        if (site.element().getAnnotation(Log.class) == null) {
            return Optional.empty();
        }
        return Optional.of(CodeBlock.of("$T.getLogger($T.class.getName())", LOGGER, ClassName.get(site.beanType())));
    }
}
