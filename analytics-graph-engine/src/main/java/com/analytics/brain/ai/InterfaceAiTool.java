package com.analytics.brain.ai;

import com.analytics.brain.data.FinalDataset;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.function.BiConsumer;

@FunctionalInterface
public interface InterfaceAiTool {

    String run(FinalDataset ds, JsonNode args, BiConsumer<String, Object> emit) throws Exception;
}
