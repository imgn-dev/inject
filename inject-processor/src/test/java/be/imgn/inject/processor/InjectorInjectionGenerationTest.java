package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class InjectorInjectionGenerationTest {

    @Test
    void injectorSiteCompilesAndRendersPlainKey() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Locator", """
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
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Locator$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"injector\", Injector.class,");
    }

    @Test
    void injectingContextIsAnError() {
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject config = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bad", """
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
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Custom", """
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
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Custom$$Factory")
                .contentsAsUtf8String();
        source.doesNotContain("Key.of(Context.class)");
    }
}
