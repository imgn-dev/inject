package be.imgn.inject.examples;

import be.imgn.inject.Inject;
import be.imgn.inject.IntoMap;

/** The {@code "xml"} entry of {@code Map<String, Formatter>}. */
@IntoMap(value = Formatter.class, key = "xml")
public final class XmlFormatter implements Formatter {
    @Inject
    public XmlFormatter() {}
}
