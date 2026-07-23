package be.imgn.inject.examples.modular;

import be.imgn.inject.Assisted;
import be.imgn.inject.Inject;

/** An assisted bean on the module path: the widget is container-resolved, the label caller-supplied. */
public final class Ticket {
    private final Widget widget;
    private final String label;

    /**
     * @param widget the container-resolved widget
     * @param label the caller-supplied label
     */
    @Inject
    public Ticket(Widget widget, @Assisted String label) {
        this.widget = widget;
        this.label = label;
    }

    /** {@return the container-resolved widget} */
    public Widget widget() {
        return widget;
    }

    /** {@return the caller-supplied label} */
    public String label() {
        return label;
    }
}
