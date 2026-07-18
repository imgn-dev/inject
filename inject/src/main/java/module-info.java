/** The zero-reflection dependency injection runtime. */
module be.imgn.inject {
    requires static org.jspecify;

    // TODO Task 2: re-add "exports be.imgn.inject;" once that package has a real (non
    // package-info-only) class; javac treats a package containing only package-info.java as
    // empty and refuses to export it.
    // TODO Task 5: re-add "exports be.imgn.inject.context;" once that package has sources.

    // TODO Task 7: re-enable once be.imgn.inject.context.Factory/Scope/SingletonScope exist.
    // uses be.imgn.inject.context.Factory;
    // uses be.imgn.inject.context.Scope;
    //
    // provides be.imgn.inject.context.Scope with be.imgn.inject.context.SingletonScope;
}
