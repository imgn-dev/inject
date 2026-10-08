package be.imgn.inject.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class GeneratedNamesTest {
    @Test
    void factoryNamesFollowTheNestingChain() {
        assertThat(GeneratedNames.factoryName("Bean")).isEqualTo("$Bean$$Factory");
        assertThat(GeneratedNames.factoryName(List.of("Bean", "SubBean"))).isEqualTo("$Bean$SubBean$$Factory");
        assertThat(GeneratedNames.factoryName(List.of("A", "B", "C"))).isEqualTo("$A$B$C$$Factory");
    }

    @Test
    void scopeNamesUseTheScopeSuffix() {
        assertThat(GeneratedNames.scopeName("Singleton")).isEqualTo("$Singleton$$Scope");
        assertThat(GeneratedNames.scopeName(List.of("Outer", "MyScope"))).isEqualTo("$Outer$MyScope$$Scope");
    }

    @Test
    void providesNamesAppendMethodAndDisambiguationIndex() {
        assertThat(GeneratedNames.providesFactoryName("Config", "provideX", 0)).isEqualTo("$Config$provideX$$Factory");
        assertThat(GeneratedNames.providesFactoryName("Config", "provideX", 1))
                .isEqualTo("$Config$provideX$1$$Factory");
    }

    @Test
    void buildsInterceptedProxyNames() {
        assertThat(GeneratedNames.interceptedName("Bean")).isEqualTo("$Bean$$Intercepted");
        assertThat(GeneratedNames.interceptedName(List.of("Outer", "Inner"))).isEqualTo("$Outer$Inner$$Intercepted");
    }
}
