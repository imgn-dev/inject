package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuildSmokeTest {
    @Test
    void moduleBuilds() {
        assertThat(getClass().getModule().getName()).isEqualTo("be.imgn.inject");
    }
}
