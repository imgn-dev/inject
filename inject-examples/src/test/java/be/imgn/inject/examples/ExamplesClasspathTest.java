package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.UnsatisfiedDependencyException;

class ExamplesClasspathTest {

    @Test
    void resolvesTheFullGraphIncludingPluginLogger() {
        Injector injector = Injector.create();

        GreetingService service = injector.getInstance(GreetingService.class);
        assertThat(service)
                .isNotNull()
                .returns("Hello", GreetingService::greeting)
                .satisfies(resolved -> {
                    assertThat(resolved.now()).isNotNull();
                    assertThat(resolved.registry()).isNotNull();
                    assertThat(resolved.logger()).isNotNull();
                    assertThat(resolved.logger().getName()).isEqualTo(GreetingService.class.getName());
                    // @Log injected through Registry's constructor, named after Registry
                    assertThat(resolved.registry().logger()).isNotNull();
                    assertThat(resolved.registry().logger().getName()).isEqualTo(Registry.class.getName());
                });
    }

    @Test
    void singletonReturnsTheSameInstance() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Registry.class)).isSameAs(injector.getInstance(Registry.class));
    }

    @Test
    void namedQualifierResolvesTheProvidedValue() {
        Injector injector = Injector.create();
        assertThat(injector.getInstance(Key.of(String.class, Names.named("greeting"))))
                .isEqualTo("Hello");
    }

    @Test
    void exposeAsMakesBeanInjectableOnlyAsListedType() {
        Injector injector = Injector.create();

        Engine engine = injector.getInstance(Engine.class);
        assertThat(engine).isNotNull().isInstanceOf(V8Engine.class);
        assertThat(injector.getInstance(Engine.class)).isSameAs(engine); // singleton
        assertThatExceptionOfType(UnsatisfiedDependencyException.class)
                .isThrownBy(() -> injector.getInstance(V8Engine.class));
    }

    @Test
    void defaultExposesBeanUnderAllOfItsTypes() {
        Injector injector = Injector.create();

        // Registry has no @ExposeAs, so it is injectable as both its own type and its Audited supertype,
        // resolving to the same singleton.
        assertThat(injector.getInstance(Audited.class)).isNotNull().isSameAs(injector.getInstance(Registry.class));
    }
}
