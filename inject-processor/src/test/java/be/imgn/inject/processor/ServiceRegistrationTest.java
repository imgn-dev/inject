package be.imgn.inject.processor;

import static be.imgn.inject.processor.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class ServiceRegistrationTest {

    @Test
    void writesFactoryServiceFileAndModulePathNote() throws Exception {
        JavaFileObject widget = TestCompiler.source("example.Widget", """
                package example;

                import be.imgn.inject.Inject;

                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(widget);

        CompilationAssert.assertThat(compilation).succeeded();

        Optional<JavaFileObject> serviceFile = compilation.generatedFile(
                StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.inject.context.Factory");
        assertThat(serviceFile).isPresent();
        assertThat(serviceFile.orElseThrow().getCharContent(true).toString()).contains("example.$Widget$$Factory");

        CompilationAssert.assertThat(compilation).hadNoteContaining("provides be.imgn.inject.context.Factory with");
    }
}
