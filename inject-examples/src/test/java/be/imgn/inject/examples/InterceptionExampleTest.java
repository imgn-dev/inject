package be.imgn.inject.examples;

import static org.assertj.core.api.Assertions.assertThat;

import be.imgn.inject.Injector;
import org.junit.jupiter.api.Test;

class InterceptionExampleTest {

    @Test
    void interceptorsRunOutermostFirstAroundTheBean() {
        try (Injector injector = Injector.create()) {
            Checkout checkout = injector.getInstance(Checkout.class);
            CallLog log = injector.getInstance(CallLog.class);

            String receipt = checkout.place("cart-1");

            assertThat(receipt).isEqualTo("placed:cart-1");
            assertThat(log.entries())
                    .containsExactly("audit:enter:place", "timed:enter:place", "timed:exit:place", "audit:exit:place");
        }
    }

    @Test
    void anInterceptorCanSubstituteArguments() {
        try (Injector injector = Injector.create()) {
            Checkout checkout = injector.getInstance(Checkout.class);

            assertThat(checkout.place("  cart-2  ")).isEqualTo("placed:cart-2");
        }
    }
}
