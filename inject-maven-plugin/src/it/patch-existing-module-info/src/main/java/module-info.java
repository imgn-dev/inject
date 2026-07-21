// Deliberately declares no `provides` clause: the inject-maven-plugin adds it to the compiled descriptor.
module it.patch {
    requires be.imgn.inject;
    exports it;
}
