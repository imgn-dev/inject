package be.imgn.inject.processor;

import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.StepProcessor;
import be.imgn.inject.processor.spi.InjectPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Generates the factories imgn inject resolves at runtime, with no reflection. The work is done by one
 * {@link InjectStep}, which receives the elements carrying the framework's annotations, and those carrying an
 * annotation meta-annotated with {@link be.imgn.inject.InjectingAnnotation} or
 * {@link be.imgn.inject.InterceptingAnnotation}. Plugins are discovered with {@link ServiceLoader} on the processor
 * path.
 */
public final class InjectProcessor extends StepProcessor {

    /** Creates the processor. Instantiated by {@link ServiceLoader}. */
    public InjectProcessor() {}

    @Override
    protected Iterable<? extends Step> steps(ProcessingContext context) {
        List<InjectPlugin> plugins = new ArrayList<>();
        ServiceLoader.load(InjectPlugin.class, getClass().getClassLoader()).forEach(plugins::add);
        return List.of(new InjectStep(context, List.copyOf(plugins)));
    }
}
