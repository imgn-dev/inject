// With no module-info and generation off, the plugin creates nothing: the factories stay discoverable through the
// ServiceLoader file the processor already writes.
File moduleInfo = new File(basedir, 'target/classes/module-info.class')
assert !moduleInfo.exists() : 'a module-info.class must not be generated when generateModuleInfo is off'

File serviceFile = new File(basedir, 'target/classes/META-INF/services/be.imgn.inject.context.Factory')
assert serviceFile.exists() : 'the processor-written service file must be present'
assert serviceFile.text.contains('it.$Widget$$Factory') : "unexpected service file:\n${serviceFile.text}"

return true
