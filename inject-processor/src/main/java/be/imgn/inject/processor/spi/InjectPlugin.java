package be.imgn.inject.processor.spi;

/**
 * Extension point for the annotation processor pipeline, discovered via {@link java.util.ServiceLoader}. Every hook is
 * a {@code default} no-op, so a plugin overrides only the stage(s) it cares about.
 *
 * <p>This interface is provisional: the hook set and context types may change as real plugins reveal what they need.
 * The default-no-op contract is the one fixed guarantee.
 */
public interface InjectPlugin {

    /**
     * Invoked after injectable elements are gathered.
     *
     * @param context the gather-stage context
     */
    default void onGather(GatherContext context) {}

    /**
     * Invoked after the dependency graph is planned and validated.
     *
     * @param context the plan-stage context
     */
    default void onPlan(PlanContext context) {}

    /**
     * Invoked when factories are generated.
     *
     * @param context the generate-stage context
     */
    default void onGenerate(GenerateContext context) {}
}
