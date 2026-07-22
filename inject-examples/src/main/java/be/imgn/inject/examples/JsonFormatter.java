package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoMap;

/** The {@code "json"} entry of {@code Map<String, Formatter>}. */
@IntoMap(value = Formatter.class, key = "json")
public final class JsonFormatter implements Formatter {
    @Inject
    public JsonFormatter() {}
}
