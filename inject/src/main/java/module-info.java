/** The zero-reflection dependency injection runtime. */
module be.imgn.inject {
    requires static org.jspecify;

    exports be.imgn.inject;
    exports be.imgn.inject.context;

    uses be.imgn.inject.context.Factory;
    uses be.imgn.inject.context.Scope;

    provides be.imgn.inject.context.Scope with be.imgn.inject.context.SingletonScope;
}
