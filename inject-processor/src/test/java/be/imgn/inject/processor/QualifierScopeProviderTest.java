package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class QualifierScopeProviderTest {

    @Test
    void generatesQualifierForNamedParameter() {
        JavaFileObject consumer = JavaFileObjects.forSourceString("example.Consumer", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Named;

                public class Consumer {
                    @Inject
                    public Consumer(@Named("main") String db) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(consumer);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Consumer$$Factory")
                .contentsAsUtf8String();
        source.contains("Names.named(\"main\")");
    }

    @Test
    void generatesScopeOverrideForSingletonBean() {
        JavaFileObject service = JavaFileObjects.forSourceString("example.Service", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Service {
                    @Inject
                    public Service() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String()
                .contains("return Optional.of(Singleton.class)");
    }

    @Test
    void generatesProviderInjectionForProviderParameter() {
        JavaFileObject lazy = JavaFileObjects.forSourceString("example.Lazy", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Provider;

                public class Lazy {
                    @Inject
                    public Lazy(Provider<String> provider) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(lazy);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Lazy$$Factory")
                .contentsAsUtf8String();
        source.contains("new Parameter(\"provider\", String.class,");
        source.contains("return new Lazy(getProviderForConstructor(0, context))");
    }

    @Test
    void generatesExposedKeysForQualifiedBean() {
        JavaFileObject tag = JavaFileObjects.forSourceString("example.Tag", """
                package example;

                import be.imgn.inject.QualifyingAnnotation;
                import java.lang.annotation.ElementType;
                import java.lang.annotation.Target;

                @QualifyingAnnotation
                @Target(ElementType.TYPE)
                public @interface Tag {
                    String value();
                }
                """);
        JavaFileObject widget = JavaFileObjects.forSourceString("example.Widget", """
                package example;

                import be.imgn.inject.Inject;

                @Tag("primary")
                public class Widget {
                    @Inject
                    public Widget() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(tag, widget);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Widget$$Factory")
                .contentsAsUtf8String();
        source.contains("Key.of(Widget.class,");
        source.contains("$example$Tag$$Impl(\"primary\")");
    }

    @Test
    void rendersIntValuedCustomQualifier() {
        JavaFileObject priority = JavaFileObjects.forSourceString("example.Priority", """
                package example;

                import be.imgn.inject.QualifyingAnnotation;

                @QualifyingAnnotation
                public @interface Priority {
                    int value();
                }
                """);
        JavaFileObject task = JavaFileObjects.forSourceString("example.Task", """
                package example;

                import be.imgn.inject.Inject;

                public class Task {
                    @Inject
                    public Task(@Priority(5) String p) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(priority, task);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Task$$Factory")
                .contentsAsUtf8String();
        source.contains("$example$Priority$$Impl(5)");
        assertThat(compilation).generatedSourceFile("example.$example$Priority$$Impl");
    }
}
