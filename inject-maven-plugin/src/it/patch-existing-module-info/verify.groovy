import java.lang.module.ModuleDescriptor

File moduleInfo = new File(basedir, 'target/classes/module-info.class')
assert moduleInfo.exists() : 'module-info.class was not compiled'

ModuleDescriptor descriptor = moduleInfo.withInputStream { ModuleDescriptor.read(it) }

def factory = descriptor.provides().find { it.service() == 'be.imgn.inject.context.Factory' }
assert factory != null : "no provides for Factory in ${descriptor.provides()}"
assert factory.providers().contains('it.$Widget$$Factory') : "unexpected providers ${factory.providers()}"

// the plugin must not disturb the rest of the descriptor
assert descriptor.name() == 'it.patch'
assert descriptor.requires().any { it.name() == 'be.imgn.inject' }
assert descriptor.exports().any { it.source() == 'it' }

return true
