package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Named;
import be.imgn.inject.Qualifier;

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
        Qualifier greeting = new Qualifier(Named.class, Map.of("value", "greeting"));
        assertThat(injector.getInstance(Key.of(String.class, greeting))).isEqualTo("Hello");
    }
}
