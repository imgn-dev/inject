package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.TestCompiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.CompilationAssert;
import be.imgn.gaia.processing.testing.TestCompiler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

class GeneratedSourceEncodingTest {

    @Test
    void nonAsciiQualifiersAreWrittenAsUnicodeEscapes() throws IOException {
        JavaFileObject consumer = TestCompiler.source("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Named;

                public class Consumer {
                    @Inject
                    public Consumer(@Named("日") String sun, @Named("月") String moon) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(consumer);

        CompilationAssert.assertThat(compilation).succeeded();
        String source = compilation
                .generatedSourceFile("example.$Consumer$$Factory")
                .orElseThrow()
                .getCharContent(true)
                .toString();
        assertThat(source)
                .as("an encoding that cannot represent 日 or 月 would otherwise turn both qualifiers into the same value")
                .matches("\\p{ASCII}*")
                .contains("Names.named(\"\\u65e5\")")
                .contains("Names.named(\"\\u6708\")");
    }

    @Test
    void serviceFileIsUtf8() throws IOException {
        JavaFileObject bean = TestCompiler.source("example.Café", """
                package example;

                import be.imgn.inject.Inject;

                public class Café {
                    @Inject
                    public Café() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        CompilationAssert.assertThat(compilation).succeeded();
        byte[] serviceFile = compilation
                .generatedFile(StandardLocation.CLASS_OUTPUT, "META-INF/services/be.imgn.inject.context.Factory")
                .orElseThrow()
                .openInputStream()
                .readAllBytes();
        assertThat(new String(serviceFile, StandardCharsets.UTF_8)).isEqualTo("example.$Café$$Factory\n");
    }
}
