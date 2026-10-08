package be.imgn.inject.processor;

import static be.imgn.inject.processor.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.inject.Injector;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndToEndTest {

    @Test
    void injectorResolvesGeneratedFactoriesViaServiceLoader(@TempDir Path outputDir) throws Exception {
        JavaFileObject widget = TestCompiler.source("example.Widget", """
                package example;

                import be.imgn.inject.Inject;

                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);
        JavaFileObject service = TestCompiler.source("example.Service", """
                package example;

                import be.imgn.inject.Inject;

                public class Service {
                    private final Widget widget;

                    @Inject
                    public Service(Widget widget) {
                        this.widget = widget;
                    }

                    public Widget widget() {
                        return widget;
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(widget, service);
        CompilationAssert.assertThat(compilation).succeeded();
        writeClassOutput(compilation, outputDir);

        try (URLClassLoader loader = new URLClassLoader(
                new URL[] {outputDir.toUri().toURL()}, getClass().getClassLoader())) {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                Injector injector = Injector.create();
                Object resolvedService = injector.getInstance(loader.loadClass("example.Service"));
                assertThat(resolvedService).isNotNull();
                assertThat(resolvedService.getClass().getName()).isEqualTo("example.Service");
                assertThat(injector.getInstance(loader.loadClass("example.Widget")))
                        .isNotNull();
            } finally {
                Thread.currentThread().setContextClassLoader(previous);
            }
        }
    }

    private static void writeClassOutput(Compilation compilation, Path outputDir) throws Exception {
        String marker = "CLASS_OUTPUT/";
        for (JavaFileObject file : compilation.generatedFiles()) {
            String name = file.getName();
            int index = name.indexOf(marker);
            if (index < 0) {
                continue;
            }
            Path target = outputDir.resolve(name.substring(index + marker.length()));
            Files.createDirectories(target.getParent());
            try (InputStream in = file.openInputStream()) {
                Files.write(target, in.readAllBytes());
            }
        }
    }
}
