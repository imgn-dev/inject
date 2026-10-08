package be.imgn.inject.processor;

import static be.imgn.inject.processor.CompilationAssert.assertThat;
import static be.imgn.inject.processor.TestCompiler.javac;

import javax.tools.JavaFileObject;
import org.assertj.core.api.AbstractStringAssert;
import org.junit.jupiter.api.Test;

class InjectorInjectionGenerationTest {

    @Test
    void injectorSiteCompilesAndRendersPlainKey() {
        JavaFileObject bean = TestCompiler.source("example.Locator", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;
                import be.imgn.inject.Provider;

                public class Locator {
                    @Inject
                    public Locator(Injector injector, Provider<Injector> lazy) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Locator$$Factory");
        source.contains("new Parameter(\"injector\", Injector.class,");
    }

    @Test
    void injectingContextIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Context;

                public class Bad {
                    @Inject
                    public Bad(Context context) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inject Injector instead");
    }

    @Test
    void injectingProviderOfContextIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;
                import be.imgn.inject.context.Context;

                public class Bad {
                    @Inject
                    public Bad(Provider<Context> context) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("inject Injector instead");
    }

    @Test
    void injectingFactoryIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Factory;

                public class Bad {
                    @Inject
                    public Bad(Factory<String> factory) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be injected");
    }

    @Test
    void providingInjectorIsAnError() {
        JavaFileObject config = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Injector;
                import be.imgn.inject.Provides;

                public class Bad {
                    @Provides
                    public static Injector injector() {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(config);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void exposeAsFrameworkTypeIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.ExposeAs;
                import be.imgn.inject.Inject;
                import be.imgn.inject.context.Context;

                @ExposeAs(Context.class)
                public class Bad implements Context {
                    @Inject
                    public Bad() {}

                    @Override
                    public <T> be.imgn.inject.Provider<T> getProvider(be.imgn.inject.Key<T> key) {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void intoSetOfFrameworkTypeIsAnError() {
        JavaFileObject bean = TestCompiler.source("example.Bad", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Injector;
                import be.imgn.inject.IntoSet;

                @IntoSet(Injector.class)
                public class Bad {
                    @Inject
                    public Bad() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("cannot be bound");
    }

    @Test
    void beanImplementingContextIsSilentlyNotExposedAsContext() {
        JavaFileObject bean = TestCompiler.source("example.Custom", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Key;
                import be.imgn.inject.Provider;
                import be.imgn.inject.context.Context;

                public class Custom implements Context {
                    @Inject
                    public Custom() {}

                    @Override
                    public <T> Provider<T> getProvider(Key<T> key) {
                        throw new UnsupportedOperationException();
                    }
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(bean);

        assertThat(compilation).succeeded();
        AbstractStringAssert<?> source = assertThat(compilation).generatedSourceFile("example.$Custom$$Factory");
        source.doesNotContain("Key.of(Context.class)");
    }
}
