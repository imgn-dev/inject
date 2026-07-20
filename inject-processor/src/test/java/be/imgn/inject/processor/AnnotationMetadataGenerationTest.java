package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import javax.tools.JavaFileObject;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;

import org.junit.jupiter.api.Test;

class AnnotationMetadataGenerationTest {

    @Test
    void capturesQualifierAndScopeAnnotationsAtTheirSites() {
        JavaFileObject service = JavaFileObjects.forSourceString("example.Service", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.Named;
                import be.imgn.inject.Singleton;

                @Singleton
                public class Service {
                    @Inject
                    public Service(@Named("db") String url) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String();
        // the bean's @Singleton lands on the constructor injection point
        source.contains("AnnotationMetadata.of(new $be$imgn$inject$Singleton$$Impl())");
        // the parameter's @Named lands on the parameter
        source.contains("AnnotationMetadata.of(Names.named(\"db\"))");
    }

    @Test
    void emitsEmptyMetadataWhenNoFrameworkAnnotationIsPresent() {
        JavaFileObject plain = JavaFileObjects.forSourceString("example.Plain", """
                package example;

                import be.imgn.inject.Inject;

                public class Plain {
                    @Inject
                    public Plain(String value) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(plain);

        assertThat(compilation).succeeded();
        assertThat(compilation)
                .generatedSourceFile("example.$Plain$$Factory")
                .contentsAsUtf8String()
                .contains("AnnotationMetadata.empty()");
    }

    @Test
    void generatesImplIntoTheUsingPackageNotTheAnnotationPackage() {
        JavaFileObject tag = JavaFileObjects.forSourceString("tags.Tag", """
                package tags;

                import be.imgn.inject.QualifyingAnnotation;

                @QualifyingAnnotation
                public @interface Tag {
                    String value();
                }
                """);
        JavaFileObject bean = JavaFileObjects.forSourceString("example.Bean", """
                package example;

                import be.imgn.inject.Inject;
                import tags.Tag;

                public class Bean {
                    @Inject
                    public Bean(@Tag("x") String value) {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(tag, bean);

        assertThat(compilation).succeeded();
        // emitted into the consuming factory's package, so it never splits the annotation's package
        assertThat(compilation).generatedSourceFile("example.$tags$Tag$$Impl");
    }
}
