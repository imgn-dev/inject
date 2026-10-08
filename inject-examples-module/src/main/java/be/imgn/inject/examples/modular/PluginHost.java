package be.imgn.inject.examples.modular;

import be.imgn.inject.Inject;
import java.util.Set;

/** Injects every contributed {@link Plugin}. */
public final class PluginHost {
    private final Set<Plugin> plugins;

    /** @param plugins every contributed plugin */
    @Inject
    public PluginHost(Set<Plugin> plugins) {
        this.plugins = plugins;
    }

    /**
     * Returns every contributed plugin.
     *
     * @return every contributed plugin
     */
    public Set<Plugin> plugins() {
        return plugins;
    }
}
