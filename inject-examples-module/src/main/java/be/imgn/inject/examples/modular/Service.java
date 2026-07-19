package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;

/** A bean depending on the singleton {@link Widget}. */
public final class Service {

    private final Widget widget;

    @Inject
    public Service(Widget widget) {
        this.widget = widget;
    }

    /** {@return the injected widget} */
    public Widget widget() {
        return widget;
    }
}
