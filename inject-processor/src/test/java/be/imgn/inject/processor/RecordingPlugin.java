package be.imgn.inject.processor;

import java.util.Optional;

import com.palantir.javapoet.CodeBlock;

import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.InjectionSite;

/** Test plugin that supplies a constant initializer for {@link Marker}-annotated fields. */
public final class RecordingPlugin implements InjectPlugin {

    public RecordingPlugin() {}

    @Override
    public Optional<CodeBlock> fieldInitializer(InjectionSite site) {
        if (site.element().getAnnotation(Marker.class) != null) {
            return Optional.of(CodeBlock.of("$S", "marked"));
        }
        return Optional.empty();
    }
}
