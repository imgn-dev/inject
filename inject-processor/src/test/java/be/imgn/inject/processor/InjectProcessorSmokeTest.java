package be.imgn.inject.processor;

import static be.imgn.gaia.processing.testing.CompilationAssert.assertThat;
import static be.imgn.gaia.processing.testing.ProcessorAssert.assertThat;
import static be.imgn.gaia.processing.testing.TestCompiler.javac;

import be.imgn.gaia.processing.testing.Compilation;
import be.imgn.gaia.processing.testing.TestCompiler;
import org.junit.jupiter.api.Test;

class InjectProcessorSmokeTest {
    @Test
    void processorRunsWithoutErrorsOnTrivialInput() {
        Compilation compilation = javac().withProcessors(new InjectProcessor())
                .compile(TestCompiler.source("example.Trivial", "package example;\n\npublic class Trivial {}\n"));

        assertThat(compilation).succeeded();
    }

    @Test
    void processorAddsNoWarningAtTheCompilersOwnSourceVersion() {
        Compilation compilation =
                javac().withProcessors(new InjectProcessor()).compile(TestCompiler.source("example.Bean", """
                        package example;

                        public class Bean {
                            @be.imgn.inject.Inject
                            public Bean() {}
                        }
                        """));

        assertThat(compilation).succeeded().hadNoWarnings();
    }

    @Test
    void processorIsRegisteredForTheCompiler() {
        assertThat(InjectProcessor.class).isRegistered();
    }
}
