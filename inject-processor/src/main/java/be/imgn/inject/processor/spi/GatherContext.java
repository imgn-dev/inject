package be.imgn.inject.processor.spi;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;

/**
 * Context passed to {@link InjectPlugin#onGather} during the gather stage.
 *
 * @param processingEnvironment the current processing environment
 * @param roundEnvironment the current round environment
 */
public record GatherContext(ProcessingEnvironment processingEnvironment, RoundEnvironment roundEnvironment) {}
