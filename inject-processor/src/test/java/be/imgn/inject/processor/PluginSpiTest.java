package be.imgn.inject.processor;

import static com.google.testing.compile.Compiler.javac;
import static org.assertj.core.api.Assertions.assertThat;

import javax.tools.JavaFileObject;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.CompilationSubject;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class PluginSpiTest {

    @Test
    void invokesRegisteredPluginHooksAtEachStage() {
        RecordingPlugin.STAGES.clear();
        JavaFileObject widget = JavaFileObjects.forSourceString("example.Widget", """
                package example;

                import be.imgn.inject.Inject;

                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(widget);

        CompilationSubject.assertThat(compilation).succeeded();
        assertThat(RecordingPlugin.STAGES).contains("gather", "plan", "generate");
    }
}
