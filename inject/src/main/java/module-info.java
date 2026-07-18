/** The zero-reflection dependency injection runtime. */
module be.imgn.inject {
    requires static org.jspecify;

    exports be.imgn.inject;

    // TODO Task 5: re-add "exports be.imgn.inject.context;" once that package has sources.

    // TODO Task 7: re-enable once be.imgn.inject.context.Factory/Scope/SingletonScope exist.
    // uses be.imgn.inject.context.Factory;
    // uses be.imgn.inject.context.Scope;
    //
    // provides be.imgn.inject.context.Scope with be.imgn.inject.context.SingletonScope;
}
