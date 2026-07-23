package be.imgn.inject.processor;

import java.util.List;

/**
 * A bean's field and method injection, gathered independently of its constructor.
 *
 * @param fields the {@code @Inject} fields
 * @param methods the {@code @Inject} methods
 * @param pluginFields the plugin-initialized fields
 */
record MemberInjection(List<FieldModel> fields, List<MethodModel> methods, List<PluginField> pluginFields) {}
