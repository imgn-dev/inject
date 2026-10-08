package be.imgn.inject.processor;

import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.common.truth.StringSubject;
import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;

class AnnotationMetadataGenerationTest {

    @Test
    void generatesBeanDefinitionMetadataOverride() {
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
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Service$$Factory")
                .contentsAsUtf8String();
        // the bean's own @Singleton is exposed through BeanDefinition.annotationMetadata(), even with a no-arg
        // constructor (which emits no constructor injection point)
        source.contains("public AnnotationMetadata annotationMetadata()");
        source.contains("AnnotationValue.of(Singleton.class, Map.of(), Set.of(ScopingAnnotation.class))");
    }

    @Test
    void capturesConditioningAnnotationWithItsStereotype() {
        JavaFileObject service = JavaFileObjects.forSourceString("example.Feature", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.ConditionalOnProperty;

                @ConditionalOnProperty(value = "feature.on", havingValue = "true")
                public class Feature {
                    @Inject
                    public Feature() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(service);

        assertThat(compilation).succeeded();
        StringSubject source = assertThat(compilation)
                .generatedSourceFile("example.$Feature$$Factory")
                .contentsAsUtf8String();
        source.contains("public AnnotationMetadata annotationMetadata()");
        source.contains("AnnotationValue.of(ConditionalOnProperty.class");
        source.contains("Map.entry(\"value\", \"feature.on\")");
        source.contains("Set.of(ConditioningAnnotation.class)");
    }

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
        // the bean's @Singleton lands on the constructor injection point, with its scoping stereotype
        source.contains("AnnotationValue.of(Singleton.class, Map.of(), Set.of(ScopingAnnotation.class))");
        // the parameter's @Named lands on the parameter, with its qualifying stereotype
        source.contains("AnnotationValue.of(Named.class, Map.ofEntries(Map.entry(\"value\", \"db\"))");
        source.contains("Set.of(QualifyingAnnotation.class)");
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
    void metadataNeedsNoGeneratedImplementationClass() {
        JavaFileObject scope = JavaFileObjects.forSourceString("example.Job", """
                package example;

                import be.imgn.inject.Inject;
                import be.imgn.inject.ScopingAnnotation;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;

                public class Job {
                    @ScopingAnnotation
                    @Retention(RetentionPolicy.RUNTIME)
                    public @interface Batched {
                        int size();
                    }

                    @Inject
                    public Job() {}
                }
                """);

        Compilation compilation = javac().withProcessors(new InjectProcessor()).compile(scope);

        assertThat(compilation).succeeded();
        // scoping/injecting annotations are recorded as data, so no implementation class is emitted for them
        assertThat(compilation)
                .generatedSourceFile("example.$Job$$Factory")
                .contentsAsUtf8String()
                .doesNotContain("$$Impl");
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
