package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InjectProcessorSmokeTest {
    @Test
    void processorRunsWithoutErrorsOnTrivialInput() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(JavaFileObjects.forSourceString(
                        "example.Trivial", "package example;\n\npublic class Trivial {}\n"));

        assertThat(compilation).succeeded();
    }
}
