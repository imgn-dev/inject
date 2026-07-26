package be.imgn.inject.processor;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
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

    /** Creates the processor. Instantiated by {@link java.util.ServiceLoader}. */
    public InjectProcessor() {}

    private final List<String> factoryNames = new ArrayList<>();
    private final Set<String> emittedAnnotationImpls = new HashSet<>();

    private @Nullable ModelFactory modelFactory;
    private @Nullable GatherStage gatherStage;
    private @Nullable PlanStage planStage;
    private @Nullable ServiceRegistrar serviceRegistrar;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        List<InjectPlugin> plugins = new ArrayList<>();
        ServiceLoader.load(InjectPlugin.class, getClass().getClassLoader()).forEach(plugins::add);
        Diagnostics diagnostics = new Diagnostics(processingEnvironment);
        ModelFactory factory = new ModelFactory(processingEnvironment, List.copyOf(plugins), diagnostics);
        this.modelFactory = factory;
        this.gatherStage = new GatherStage(factory, diagnostics);
        this.planStage = new PlanStage(factory, diagnostics);
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
            if (model.interception() != null) {
                writeFactory(InterceptedProxyGenerator.generate(model));
            }
            writeFactory(BeanFactoryGenerator.generate(model));
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        for (ProvidesModel model : gathered.provides()) {
            writeFactory(ProvidesFactoryGenerator.generate(model));
            factoryNames.add(model.packageName() + "." + model.factorySimpleName());
        }
        for (AssistedFactoryModel model : gathered.assistedFactories()) {
            AssistedFactoryGenerator.Generated generated = AssistedFactoryGenerator.generate(
                    model, Objects.requireNonNull(modelFactory), processingEnv.getElementUtils());
            writeFactory(generated.implFile());
            writeFactory(generated.factoryFile());
            factoryNames.add(generated.factoryQualifiedName());
        }
        writeAnnotationImpls();
        return false;
    }

    private void writeAnnotationImpls() {
        for (AnnotationImplRequest request :
                Objects.requireNonNull(modelFactory).drainAnnotationImplRequests()) {
            String qualifiedName =
                    request.targetPackage() + "." + AnnotationImplGenerator.implSimpleName(request.annotationType());
            if (emittedAnnotationImpls.add(qualifiedName)) {
                writeFactory(AnnotationImplGenerator.generate(request.annotationType(), request.targetPackage()));
            }
        }
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
