package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;
import be.imgn.inject.Key;
import be.imgn.inject.Names;
import be.imgn.inject.ScopingAnnotation;
import be.imgn.inject.Singleton;
import be.imgn.inject.UnsatisfiedDependencyException;
import be.imgn.inject.context.BeanDefinition;

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
    void introspectsBeanDefinitions() {
        Injector injector = Injector.create();

        // scoped beans are discoverable by their scoping stereotype
        assertThat(injector.getBeanDefinitionsByStereotype(ScopingAnnotation.class))
                .extracting(BeanDefinition::key)
                .contains(Key.of(Registry.class), Key.of(V8Engine.class));

        // a definition carries its own scope annotation and its constructor injection point
        assertThat(injector.getBeanDefinition(Key.of(Registry.class))).hasValueSatisfying(definition -> {
            assertThat(definition.annotationMetadata().isPresent(Singleton.class))
                    .isTrue();
            assertThat(definition.injectionPoints()).isNotEmpty();
        });

        assertThat(injector.getBeanDefinition(Key.of(Long.class))).isEmpty();
    }

    @Test
    void conditionalBeanFollowsTheProperty() {
        // absent by default
        assertThat(Injector.create().getBeanDefinition(Key.of(PremiumFeature.class)))
                .isEmpty();

        System.setProperty("examples.premium", "true");
        try {
            assertThat(Injector.create().getInstance(PremiumFeature.class)).isNotNull();
        } finally {
            System.clearProperty("examples.premium");
        }
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

    @Test
    void collectsMultibindings() {
        NotificationCenter center = Injector.create().getInstance(NotificationCenter.class);
        assertThat(center.notifiers())
                .hasSize(2)
                .hasAtLeastOneElementOfType(EmailNotifier.class)
                .hasAtLeastOneElementOfType(SmsNotifier.class);
        assertThat(center.formatters()).containsOnlyKeys("json", "xml");

        System.setProperty("examples.push", "true");
        try {
            assertThat(Injector.create().getInstance(NotificationCenter.class).notifiers())
                    .hasSize(3)
                    .hasAtLeastOneElementOfType(PushNotifier.class);
        } finally {
            System.clearProperty("examples.push");
        }
    }

    @Test
    void injectsTheInjectorItself() {
        Injector injector = Injector.create();
        BeanLocator locator = injector.getInstance(BeanLocator.class);
        assertThat(locator.injector()).isSameAs(injector);
        assertThat(locator.locateRegistry()).isSameAs(injector.getInstance(Registry.class));
    }

    @Test
    void assistedFactoriesMixContainerAndCallerArguments() {
        Injector injector = Injector.create();

        BadgeFactory badges = injector.getInstance(BadgeFactory.class);
        Badge badge = badges.create("olivier", "admin");
        assertThat(badge)
                .returns("olivier", Badge::owner)
                .returns("admin", Badge::role)
                .returns(injector.getInstance(Registry.class), Badge::registry); // singleton respected
        assertThat(badges.create("olivier", "admin")).isNotSameAs(badge); // fresh per call

        LegacyStamp stamp = injector.getInstance(StampFactory.class).create("world");
        assertThat(stamp.text()).isEqualTo("Hello world");
    }
}
