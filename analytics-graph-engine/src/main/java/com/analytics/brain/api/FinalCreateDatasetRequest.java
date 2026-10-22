package com.analytics.brain.api;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

public final class FinalCreateDatasetRequest {

    public String name;
    public String csv;
    public String url;
    public JsonNode data;
    public Map<String, String> headers;
}
