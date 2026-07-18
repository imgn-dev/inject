package be.imgn.inject.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Provider;

class SingletonProviderTest {
    @Test
    void instantiatesExactlyOnceUnderContention() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Provider<Object> singleton = new SingletonProvider<>(() -> {
            calls.incrementAndGet();
            return new Object();
        });

        var results = IntStream.range(0, 64)
                .parallel()
                .mapToObj(i -> singleton.get())
                .distinct()
                .toList();

        assertThat(calls).hasValue(1);
        assertThat(results).hasSize(1);
    }
}
