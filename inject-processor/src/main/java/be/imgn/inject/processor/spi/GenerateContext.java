package be.imgn.inject.processor.spi;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;

/**
 * Context passed to {@link InjectPlugin#onGenerate} during the generate stage.
 *
 * @param processingEnvironment the current processing environment
 * @param roundEnvironment the current round environment
 */
public record GenerateContext(ProcessingEnvironment processingEnvironment, RoundEnvironment roundEnvironment) {}
