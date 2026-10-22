package com.analytics.brain.data;

import com.analytics.brain.exception.ExceptionAnalitikData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class FinalJsonParser implements InterfaceDataParser {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public void parse(FinalDataset ds, String text) throws Exception {
        JsonNode root = JSON.readTree(text);
        JsonNode array = findArray(root, 0);
        if (array == null) throw new ExceptionAnalitikData("В JSON не найден массив объектов");

        List<Map<String, String>> rows = new ArrayList<>();
        for (JsonNode item : array) rows.add(flatObject(item));
        FinalDataLoader.setRows(ds, rows);
    }
    public static Map<String, String> flatObject(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        flatten("", node, out);
        return out;
    }
    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull()) {
            if (!prefix.isEmpty()) out.put(prefix, "");
            return;
        }
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String key = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
                flatten(key, field.getValue(), out);
            }
        } else if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : node) {
                if (joined.length() > 0) joined.append(", ");
                joined.append(item.isValueNode() ? item.asText() : item.toString());
            }
            out.put(prefix, joined.toString());
        } else {
            out.put(prefix.isEmpty() ? "value" : prefix, node.asText());
        }
    }
    private static JsonNode findArray(JsonNode node, int depth) {
        if (node == null || depth > 4) return null;

        if (node.isArray()) {
            if (node.size() == 0) return null;
            if (node.get(0).isObject()) return node;
            if (node.get(0).isArray()) return arrayOfArrays(node);
            return null;
        }

        if (node.isObject()) {
            String[] usualNames = {"data", "items", "results", "rows", "records", "users", "events", "values"};
            for (String name : usualNames) {
                if (node.has(name)) {
                    JsonNode found = findArray(node.get(name), depth + 1);
                    if (found != null) return found;
                }
            }
            Iterator<JsonNode> children = node.elements();
            while (children.hasNext()) {
                JsonNode found = findArray(children.next(), depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }
    private static JsonNode arrayOfArrays(JsonNode rows) {
        ArrayNode out = JSON.createArrayNode();
        JsonNode header = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            ObjectNode obj = JSON.createObjectNode();
            JsonNode row = rows.get(i);
            for (int j = 0; j < header.size(); j++) {
                obj.set(header.get(j).asText(), j < row.size() ? row.get(j) : null);
            }
            out.add(obj);
        }
        return out;
    }
}
