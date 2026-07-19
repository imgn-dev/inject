package be.imgn.inject.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;

import com.palantir.javapoet.JavaFile;

import org.jspecify.annotations.Nullable;

import be.imgn.inject.processor.spi.InjectPlugin;

/**
 * Orchestrates the zero-reflection factory-generation pipeline. Each round runs {@link GatherStage} then
 * {@link PlanStage}, generates factories with {@link BeanFactoryGenerator}/{@link ProvidesFactoryGenerator}, and
 * accumulates their names. The {@link ServiceRegistrar} writes the {@code ServiceLoader} registration once, in the
 * final round.
 */
@SupportedAnnotationTypes("*")
public final class InjectProcessor extends AbstractProcessor {

    private final List<String> factoryNames = new ArrayList<>();

    private @Nullable GatherStage gatherStage;
    private @Nullable PlanStage planStage;
    private @Nullable ServiceRegistrar serviceRegistrar;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        List<InjectPlugin> plugins = new ArrayList<>();
        ServiceLoader.load(InjectPlugin.class, getClass().getClassLoader()).forEach(plugins::add);
        Diagnostics diagnostics = new Diagnostics(processingEnvironment);
        ModelFactory modelFactory = new ModelFactory(processingEnvironment, List.copyOf(plugins), diagnostics);
        this.gatherStage = new GatherStage(modelFactory, diagnostics);
        this.planStage = new PlanStage(modelFactory, diagnostics);
        this.serviceRegistrar = new ServiceRegistrar(processingEnvironment, diagnostics);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.RELEASE_21;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            Objects.requireNonNull(serviceRegistrar).register(factoryNames);
            return false;
        }
        Gathered gathered = Objects.requireNonNull(gatherStage).gather(annotations, roundEnv);
        if (!Objects.requireNonNull(planStage).plan(gathered, roundEnv)) {
            return false;
        }
        for (BeanModel model : gathered.beans().values()) {
            writeFactory(BeanFactoryGenerator.generate(model));
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        for (ProvidesModel model : gathered.provides()) {
            writeFactory(ProvidesFactoryGenerator.generate(model));
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        return false;
    }

    private void writeFactory(JavaFile file) {
        try {
            file.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory: " + e.getMessage());
        }
    }
}
