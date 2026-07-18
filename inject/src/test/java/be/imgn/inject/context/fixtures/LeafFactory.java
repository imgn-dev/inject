package be.imgn.inject.context.fixtures;

import be.imgn.inject.context.Context;
import be.imgn.inject.context.Factory;

/** Prototype leaf bean. */
public final class LeafFactory extends Factory<String> {
    public LeafFactory() {
        super(String.class);
    }

    @Override
    protected String createInstance(Context context) {
        return "leaf";
    }
}
