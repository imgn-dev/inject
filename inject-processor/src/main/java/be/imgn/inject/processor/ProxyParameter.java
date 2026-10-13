package be.imgn.inject.processor;

import be.imgn.gaia.processing.source.TypeName;

/**
 * One parameter of a generated proxy signature.
 *
 * @param name the parameter name
 * @param type the declared type, verbatim from the source
 */
record ProxyParameter(String name, TypeName type) {}
