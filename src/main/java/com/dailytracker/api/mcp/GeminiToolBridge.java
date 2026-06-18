package com.dailytracker.api.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the in-process MCP tool callbacks to the Google GenAI (Gemini) function-calling API.
 *
 * <p>This is what makes "Gemini via MCP" real: Gemini stays the LLM, but its tools come from the
 * exact same {@link ToolCallback} registry that the MCP server exposes to external clients —
 * a single source of truth. The bridge translates each tool's JSON Schema into a Gemini
 * {@link Schema} and routes Gemini function calls back through {@link ToolCallback#call(String)}.
 */
@Component
@Slf4j
public class GeminiToolBridge {

    private final ToolCallbackProvider toolCallbackProvider;
    private final ObjectMapper objectMapper;

    private List<ToolCallback> callbacks;
    private Map<String, ToolCallback> byName;

    public GeminiToolBridge(
            @Qualifier("dailyTrackerToolCallbackProvider") ToolCallbackProvider toolCallbackProvider,
            ObjectMapper objectMapper) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        this.callbacks = List.of(toolCallbackProvider.getToolCallbacks());
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback cb : callbacks) {
            map.put(cb.getToolDefinition().name(), cb);
        }
        this.byName = map;
    }

    /**
     * Builds Gemini function declarations from the MCP tools. The dynamic stage names are injected
     * as an {@code enum} on stage-name parameters so Gemini picks valid columns (the external MCP
     * path relies on {@code list_stages} + server-side validation instead).
     */
    public List<FunctionDeclaration> buildFunctionDeclarations(List<String> stageNames) {
        List<FunctionDeclaration> declarations = new ArrayList<>();
        for (ToolCallback cb : callbacks) {
            var def = cb.getToolDefinition();
            try {
                JsonNode root = objectMapper.readTree(def.inputSchema());
                Schema params = nodeToSchema(root, null, stageNames);
                declarations.add(FunctionDeclaration.builder()
                        .name(def.name())
                        .description(def.description())
                        .parameters(params)
                        .build());
            } catch (Exception e) {
                log.error("Failed to build Gemini declaration for tool {}", def.name(), e);
            }
        }
        return declarations;
    }

    /** Executes an MCP tool by name with the Gemini-provided arguments. */
    public Map<String, Object> dispatch(String name, Map<String, Object> args) {
        ToolCallback cb = byName.get(name);
        if (cb == null) {
            return Map.of("error", "unknown tool: " + name);
        }
        try {
            String input = objectMapper.writeValueAsString(args != null ? args : Map.of());
            String output = cb.call(input);
            return toResponseMap(output);
        } catch (Exception e) {
            log.error("MCP tool '{}' failed", name, e);
            return Map.of("error", e.getMessage() != null ? e.getMessage() : "tool execution error");
        }
    }

    // ── JSON Schema → Gemini Schema ─────────────────────────────────────────────

    private Schema nodeToSchema(JsonNode node, String propName, List<String> stageNames) {
        String type = resolveType(node);
        Schema.Builder b = Schema.builder().type(type);

        if (node.hasNonNull("description")) {
            b.description(node.get("description").asText());
        }

        List<String> enumValues = null;
        if (node.has("enum") && node.get("enum").isArray()) {
            enumValues = new ArrayList<>();
            for (JsonNode e : node.get("enum")) enumValues.add(e.asText());
        }
        // Inject live stage names on stage-name parameters for the internal Gemini path.
        if (("status".equals(propName) || "targetStage".equals(propName))
                && stageNames != null && !stageNames.isEmpty()) {
            enumValues = stageNames;
        }
        if (enumValues != null && !enumValues.isEmpty()) {
            b.enum_(enumValues);
        }

        if ("OBJECT".equals(type)) {
            JsonNode props = node.get("properties");
            if (props != null && props.isObject()) {
                Map<String, Schema> propsMap = new LinkedHashMap<>();
                for (Map.Entry<String, JsonNode> entry : props.properties()) {
                    propsMap.put(entry.getKey(), nodeToSchema(entry.getValue(), entry.getKey(), stageNames));
                }
                b.properties(propsMap);
            } else {
                b.properties(Map.of());
            }
            if (node.has("required") && node.get("required").isArray()) {
                List<String> required = new ArrayList<>();
                for (JsonNode r : node.get("required")) required.add(r.asText());
                if (!required.isEmpty()) b.required(required);
            }
        } else if ("ARRAY".equals(type)) {
            JsonNode items = node.get("items");
            if (items != null) {
                b.items(nodeToSchema(items, null, stageNames));
            }
        }
        return b.build();
    }

    private String resolveType(JsonNode node) {
        JsonNode typeNode = node.get("type");
        String raw = null;
        if (typeNode != null) {
            if (typeNode.isArray()) {
                for (JsonNode e : typeNode) {
                    if (!"null".equals(e.asText())) { raw = e.asText(); break; }
                }
            } else {
                raw = typeNode.asText();
            }
        }
        if (raw == null) {
            raw = node.has("properties") ? "object" : "string";
        }
        return switch (raw) {
            case "integer" -> "INTEGER";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "array" -> "ARRAY";
            case "object" -> "OBJECT";
            default -> "STRING";
        };
    }

    private Map<String, Object> toResponseMap(String output) throws Exception {
        if (output == null || output.isBlank()) {
            return Map.of("result", "");
        }
        JsonNode node = objectMapper.readTree(output);
        if (node.isObject()) {
            return objectMapper.convertValue(node, new TypeReference<Map<String, Object>>() {});
        }
        return Map.of("result", objectMapper.convertValue(node, Object.class));
    }
}
