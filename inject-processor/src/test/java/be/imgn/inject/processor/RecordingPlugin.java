package be.imgn.inject.processor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import be.imgn.inject.processor.spi.GatherContext;
import be.imgn.inject.processor.spi.GenerateContext;
import be.imgn.inject.processor.spi.InjectPlugin;
import be.imgn.inject.processor.spi.PlanContext;

/** Test plugin that records which pipeline stages were invoked. */
public final class RecordingPlugin implements InjectPlugin {

    static final Set<String> STAGES = ConcurrentHashMap.newKeySet();

    public RecordingPlugin() {}

    @Override
    public void onGather(GatherContext context) {
        STAGES.add("gather");
    }

    @Override
    public void onPlan(PlanContext context) {
        STAGES.add("plan");
    }

    @Override
    public void onGenerate(GenerateContext context) {
        STAGES.add("generate");
    }
}
