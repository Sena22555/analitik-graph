package com.analytics.brain.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class FinalChatRequest {

    public String datasetId;
    public String model;
    public List<Map<String, Object>> messages = new ArrayList<>();
    public Map<String, Object> context;
}
