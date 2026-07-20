package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import be.imgn.inject.Injector;

class ScopeExampleTest {

    @Test
    void threadScopeReturnsOneInstancePerThread() throws Exception {
        Injector injector = Injector.create();

        RequestScopedToken onThisThread = injector.getInstance(RequestScopedToken.class);
        assertThat(injector.getInstance(RequestScopedToken.class))
                .as("same thread reuses the instance")
                .isSameAs(onThisThread);

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<RequestScopedToken> onOtherThread =
                    worker.submit(() -> injector.getInstance(RequestScopedToken.class));
            assertThat(onOtherThread.get())
                    .as("another thread gets its own instance")
                    .isNotSameAs(onThisThread);
        } finally {
            worker.shutdown();
        }
    }
}
