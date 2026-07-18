package be.imgn.inject;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

class QualifierTest {
    @Test
    void markerQualifiersMatchByTypeAlone() {
        assertThat(Qualifier.of(Named.class))
                .isEqualTo(Qualifier.of(Named.class))
                .isEqualTo(new Qualifier(Named.class, Map.of()));
    }
}
