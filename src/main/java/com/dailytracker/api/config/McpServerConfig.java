package com.dailytracker.api.config;

import com.dailytracker.api.mcp.tool.DailyTrackerMcpTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the {@link DailyTrackerMcpTools} {@code @Tool} methods with the MCP server.
 *
 * <p>The Spring AI MCP server auto-configuration discovers this {@link ToolCallbackProvider} bean
 * and exposes its callbacks as MCP tools over the HTTP/SSE transport. The same tool object is also
 * adapted in-process for the Gemini path, keeping a single source of truth for the tool surface.
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider dailyTrackerToolCallbackProvider(DailyTrackerMcpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }
}
