package be.imgn.inject.examples;

import be.imgn.inject.ExposeAs;
import be.imgn.inject.Inject;
import be.imgn.inject.Singleton;

/** Exposed only as {@link Engine} — its own type and {@code Serializable} are not injectable. */
@Singleton
@ExposeAs(Engine.class)
public final class V8Engine implements Engine {

    @Inject
    public V8Engine() {}
}
