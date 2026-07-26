package be.imgn.inject.processor;

import com.palantir.javapoet.TypeName;

/**
 * One parameter of a generated proxy signature.
 *
 * @param name the parameter name
 * @param type the declared type, verbatim from the source
 */
record ProxyParameter(String name, TypeName type) {}
