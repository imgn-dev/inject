package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class PluginSpiTest {

    @Test
    void pluginInitializesInjectingAnnotationField() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Tagged", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.processor.Marker;

                public class Tagged {
                    @Marker
                    String tag;

                    @Inject
                    public Tagged() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Tagged$$Factory")
                .contentsAsUtf8String();
        source.contains("instance.tag = \"marked\"");
        source.doesNotContain("fieldInjectionPoints");
    }
}
