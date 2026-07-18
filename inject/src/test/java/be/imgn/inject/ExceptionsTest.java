package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExceptionsTest {
    @Test
    void subtypesAreInjectionExceptions() {
        assertThat(new UnsatisfiedDependencyException("x"))
                .isInstanceOf(InjectionException.class)
                .hasMessage("x");
        assertThat(new CircularDependencyException("a -> b -> a"))
                .isInstanceOf(InjectionException.class)
                .hasMessageContaining("-> a");
    }
}
