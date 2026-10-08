package be.imgn.inject.examples;

import be.imgn.inject.Inject;

/** Intercepted bean: {@code @Audited} on the class wraps {@code @Timed} on the method. */
@Audited
public class Checkout {

    /** Creates a checkout. */
    @Inject
    public Checkout() {}

    /**
     * Returns a receipt for {@code cart}.
     *
     * @param cart the cart identifier
     * @return a receipt for {@code cart}
     */
    @Timed("place")
    public String place(String cart) {
        return "placed:" + cart;
    }
}
