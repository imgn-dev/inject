package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.JavaFileObject;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

class MultiRoundTest {

    @Test
    void beanWaitsForADependencyAnotherProcessorGenerates() {
        JavaFile clock = new JavaFile("example.Clock", """
                package example;

                public class Clock {
                    public Clock() {}
                }
                """);
        JavaFileObject service = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    @Inject
                    public Service(Clock clock) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new GeneratingProcessor(clock), new InjectProcessor())
                .compile(service);

        assertThat(compilation).succeeded().hadNoWarnings();
        assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contains("this.<Clock>getInstanceForConstructor(0, Clock.class, context)");
        assertThat(compilation).generatedSourceFile("example.$Clock$$Factory").contains("return new Clock()");
    }

    @Test
    void noDefaultFactoryInALaterRoundForATypeAProvidesMethodBinds() {
        JavaFile late = new JavaFile("example.Late", """
                package example;

                import be.imgn.inject.Inject;

                public class Late {
                    @Inject
                    public Late(Config config) {}
                }
                """);
        JavaFileObject config = TestCompiler.source("example.Config", """
                package example;

                public class Config {
                    public Config() {}
                }
                """);
        JavaFileObject module = TestCompiler.source("example.ConfigModule", """
                package example;

                import be.imgn.inject.Provides;

                public class ConfigModule {
                    @Provides
                    public static Config config() {
                        return new Config();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new GeneratingProcessor(late), new InjectProcessor())
                .compile(config, module);

        assertThat(compilation).succeeded();
        assertThat(compilation).generatedSourceFile("example.$Late$$Factory").contains("Config.class");
        Assertions.assertThat(compilation.generatedSourceFiles())
                .as("a generated Config() factory would duplicate the @Provides binding")
                .noneMatch(file -> file.getName().contains("$Config$$Factory"));
    }

    /** A source file for {@link GeneratingProcessor} to generate. */
    private record JavaFile(String qualifiedName, String text) {}

    /** Generates one source file in the first round, as another processor of the build would. */
    private static final class GeneratingProcessor extends AbstractProcessor {
        private final JavaFile file;
        private boolean generated;

        GeneratingProcessor(JavaFile file) {
            this.file = file;
        }

        @Override
        public Set<String> getSupportedAnnotationTypes() {
            return Set.of("*");
        }

        @Override
        public SourceVersion getSupportedSourceVersion() {
            return SourceVersion.latestSupported();
        }

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
            if (!generated) {
                generated = true;
                try (Writer writer = processingEnv
                        .getFiler()
                        .createSourceFile(file.qualifiedName())
                        .openWriter()) {
                    writer.write(file.text());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return false;
        }
    }
}
