import java.lang.module.ModuleDescriptor

File moduleInfo = new File(basedir, 'target/classes/module-info.class')
assert moduleInfo.exists() : 'module-info.class was not generated'

ModuleDescriptor descriptor = moduleInfo.withInputStream { ModuleDescriptor.read(it) }

assert descriptor.name() == 'it.generated' : "unexpected module name ${descriptor.name()}"
assert descriptor.requires().any { it.name() == 'be.imgn.inject' } : 'generated module must require be.imgn.inject'
assert descriptor.exports().any { it.source() == 'it' } : "package 'it' not exported"

def factory = descriptor.provides().find { it.service() == 'be.imgn.inject.context.Factory' }
assert factory != null : "no provides for Factory in ${descriptor.provides()}"
assert factory.providers().contains('it.$Service$$Factory') : "unexpected factory providers ${factory.providers()}"

// the custom scope must be registered too
def scope = descriptor.provides().find { it.service() == 'be.imgn.inject.context.Scope' }
assert scope != null : "no provides for Scope in ${descriptor.provides()}"
assert scope.providers().contains('it.ThreadScope') : "unexpected scope providers ${scope.providers()}"

return true
