package be.imgn.inject.processor;

import be.imgn.inject.processor.spi.InjectPlugin;
import com.palantir.javapoet.JavaFile;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrates the zero-reflection factory-generation pipeline. Each round runs {@link GatherStage} then
 * {@link PlanStage}, generates factories with {@link BeanFactoryGenerator}/{@link ProvidesFactoryGenerator}, and
 * accumulates their names. The {@link ServiceRegistrar} writes the {@code ServiceLoader} registration once, in the
 * final round.
 */
@SupportedAnnotationTypes("*")
public final class InjectProcessor extends AbstractProcessor {

    private static final char LAST_ASCII = 0x7f;

    private final List<String> factoryNames = new ArrayList<>();
    private final Set<String> emittedAnnotationImpls = new HashSet<>();

    private @Nullable ModelFactory modelFactory;
    private @Nullable GatherStage gatherStage;
    private @Nullable PlanStage planStage;
    private @Nullable ServiceRegistrar serviceRegistrar;

    /** Creates the processor. Instantiated by {@link java.util.ServiceLoader}. */
    public InjectProcessor() {}

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

    /**
     * Supports every source version the running compiler supports.
     *
     * <p>The processor reads only declarations and annotations, which newer language versions extend without changing
     * what it relies on. Anything older than this would make javac warn on every build with a newer {@code -source},
     * which fails builds that use {@code -Werror}.
     *
     * @return the latest source version of the running compiler
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
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
        String simpleName = Objects.requireNonNull(file.typeSpec().name(), "generated type name");
        String qualifiedName = file.packageName().isEmpty() ? simpleName : file.packageName() + "." + simpleName;
        try {
            JavaFileObject source = processingEnv
                    .getFiler()
                    .createSourceFile(
                            qualifiedName, file.typeSpec().originatingElements().toArray(Element[]::new));
            try (Writer writer = source.openWriter()) {
                writer.write(asciiOnly(file.toString()));
            }
        } catch (IOException e) {
            processingEnv
                    .getMessager()
                    .printMessage(Diagnostic.Kind.ERROR, "Failed to write factory: " + e.getMessage());
        }
    }

    /**
     * {@return {@code source} with every non-ASCII character written as a Unicode escape}
     *
     * <p>The Filer encodes generated sources with javac's {@code -encoding}, which replaces any character it cannot
     * represent: two distinct string literals, such as qualifier names or map keys, could then compile to the same
     * value. A Unicode escape is plain ASCII and denotes the same character under every encoding.
     */
    static String asciiOnly(String source) {
        StringBuilder escaped = new StringBuilder(source.length());
        for (int index = 0; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character <= LAST_ASCII) {
                escaped.append(character);
            } else {
                escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            }
        }
        return escaped.toString();
    }
}
