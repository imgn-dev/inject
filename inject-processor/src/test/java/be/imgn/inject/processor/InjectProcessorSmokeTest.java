package be.imgn.inject.processor;

import static be.imgn.inject.processor.CompilationAssert.assertThat;
import static be.imgn.inject.processor.TestCompiler.javac;

import org.junit.jupiter.api.Test;

class InjectProcessorSmokeTest {
    @Test
    void processorRunsWithoutErrorsOnTrivialInput() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(TestCompiler.source("example.Trivial", "package example;\n\npublic class Trivial {}\n"));

        assertThat(compilation).succeeded();
    }
}
