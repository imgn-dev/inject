package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FactoryLifecycleTest {
    @Test
    void postConstructRunsLastInBuild() {
        Bean bean = new TrackingFactory().build(new DefaultContext(List.of(), List.of()));
        assertThat(bean.events).containsExactly("construct", "fields", "postConstruct");
    }

    @Test
    void defaultsAreInertNoOps() {
        Factory<Object> plain = new Factory<>(Object.class) {
            @Override
            protected Object createInstance(Context context) {
                return new Object();
            }
        };
        assertThat(plain.hasPreDestroy()).isFalse();
        // no-op hooks must not throw
        plain.preDestroy(new Object());
    }

    @Test
    void preDestroyHookInvokesTheCallback() {
        TrackingFactory factory = new TrackingFactory();
        Bean bean = factory.build(new DefaultContext(List.of(), List.of()));
        bean.events.clear();
        factory.preDestroy(bean);
        assertThat(bean.events).containsExactly("preDestroy");
    }

    /** Records the order of construction, injection, and the postConstruct hook. */
    static final class Bean {
        private final List<String> events = new ArrayList<>();
    }

    static final class TrackingFactory extends Factory<Bean> {
        TrackingFactory() {
            super(Bean.class);
        }

        @Override
        protected Bean createInstance(Context context) {
            Bean bean = new Bean();
            bean.events.add("construct");
            return bean;
        }

        @Override
        protected void injectFields(Bean instance, Context context) {
            instance.events.add("fields");
        }

        @Override
        protected void postConstruct(Bean instance) {
            instance.events.add("postConstruct");
        }

        @Override
        protected boolean hasPreDestroy() {
            return true;
        }

        @Override
        protected void preDestroy(Bean instance) {
            instance.events.add("preDestroy");
        }
    }
}
