package be.imgn.inject.processor;

import be.imgn.gaia.processing.AnnotatedElements;
import be.imgn.gaia.processing.ProcessingContext;
import be.imgn.gaia.processing.Step;
import be.imgn.gaia.processing.source.JavaFile;
import be.imgn.inject.AssistedFactory;
import be.imgn.inject.Default;
import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.InjectingAnnotation;
import be.imgn.inject.InterceptingAnnotation;
import be.imgn.inject.Intercepts;
import be.imgn.inject.IntoMap;
import be.imgn.inject.IntoSet;
import be.imgn.inject.Provides;
import be.imgn.inject.processor.spi.InjectPlugin;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.lang.model.element.Element;

/**
 * The processor's single step. Each round runs {@link GatherStage} then {@link PlanStage}, generates factories with
 * {@link BeanFactoryGenerator}/{@link ProvidesFactoryGenerator}, and registers them with the {@link ServiceRegistrar},
 * which notes the module-path directives after the last round.
 */
final class InjectStep implements Step {

    private final ProcessingContext context;
    private final ModelFactory modelFactory;
    private final GatherStage gatherStage;
    private final PlanStage planStage;
    private final ServiceRegistrar serviceRegistrar;

    InjectStep(ProcessingContext context, List<InjectPlugin> plugins) {
        this.context = context;
        this.modelFactory = new ModelFactory(context, plugins);
        this.gatherStage = new GatherStage(context, modelFactory, context.diagnostics());
        this.planStage = new PlanStage(context, modelFactory, context.diagnostics());
        this.serviceRegistrar = new ServiceRegistrar(context);
    }

    @Override
    public Set<String> annotations() {
        return Stream.of(
                        Inject.class,
                        Intercepts.class,
                        Provides.class,
                        AssistedFactory.class,
                        ExposeAs.class,
                        IntoSet.class,
                        IntoMap.class,
                        Default.class)
                .map(Class::getCanonicalName)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** {@return the meta-annotations of the annotations plugins and users declare: injecting and intercepting ones} */
    @Override
    public Set<String> metaAnnotations() {
        return Set.of(InjectingAnnotation.class.getCanonicalName(), InterceptingAnnotation.class.getCanonicalName());
    }

    @Override
    public Set<? extends Element> process(AnnotatedElements elements) {
        Gathered gathered = gatherStage.gather(elements);
        Set<Element> deferred =
                gathered.deferred().values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
        if (!planStage.plan(gathered)) {
            return deferred;
        }
        for (BeanModel model : gathered.beans().values()) {
            if (model.interception() != null) {
                write(InterceptedProxyGenerator.generate(model), model.origin());
            }
            JavaFile factory = BeanFactoryGenerator.generate(model);
            write(factory, model.origin());
            serviceRegistrar.register(factory.className(), model.origin());
        }
        for (ProvidesModel model : gathered.provides()) {
            JavaFile factory = ProvidesFactoryGenerator.generate(model);
            write(factory, model.origin());
            serviceRegistrar.register(factory.className(), model.origin());
        }
        for (AssistedFactoryModel model : gathered.assistedFactories()) {
            AssistedFactoryGenerator.Generated generated =
                    AssistedFactoryGenerator.generate(model, modelFactory, context.elements());
            write(generated.implFile(), model.factoryInterface());
            write(generated.factoryFile(), model.factoryInterface());
            serviceRegistrar.register(generated.factoryFile().className(), model.factoryInterface());
        }
        for (AnnotationImplRequest request : modelFactory.drainAnnotationImplRequests()) {
            write(
                    AnnotationImplGenerator.generate(request.annotationType(), request.targetPackage()),
                    request.origin());
        }
        return deferred;
    }

    @Override
    public void processingOver() {
        gatherStage.reportPendingFactories();
        serviceRegistrar.finish();
    }

    /** Writes a generated source, unless an earlier request already wrote it. */
    private void write(JavaFile file, Element origin) {
        try {
            context.sources().write(file, origin);
        } catch (UncheckedIOException e) {
            context.diagnostics()
                    .on(origin)
                    .error("Failed to write " + file.className().canonicalName() + ": " + e.getMessage());
        }
    }
}
