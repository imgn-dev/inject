package be.imgn.inject.examples.modular;

import java.util.Set;

import be.imgn.inject.Inject;

/** Injects every contributed {@link Plugin}. */
public final class PluginHost {
    private final Set<Plugin> plugins;

    /** @param plugins every contributed plugin */
    @Inject
    public PluginHost(Set<Plugin> plugins) {
        this.plugins = plugins;
    }

    /** {@return every contributed plugin} */
    public Set<Plugin> plugins() {
        return plugins;
    }
}
