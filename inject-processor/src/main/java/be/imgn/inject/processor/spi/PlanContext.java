package be.imgn.inject.processor.spi;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;

/**
 * Context passed to {@link InjectPlugin#onPlan} during the plan stage.
 *
 * @param processingEnvironment the current processing environment
 * @param roundEnvironment the current round environment
 */
public record PlanContext(ProcessingEnvironment processingEnvironment, RoundEnvironment roundEnvironment) {}
