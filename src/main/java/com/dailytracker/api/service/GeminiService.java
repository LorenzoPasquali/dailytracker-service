package com.dailytracker.api.service;

import com.dailytracker.api.dto.response.ChatResponse;
import com.dailytracker.api.entity.Stage;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.mcp.GeminiToolBridge;
import com.dailytracker.api.mcp.McpPrincipalContext;
import com.dailytracker.api.repository.StageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

/**
 * Drives the Gemini chat loop. Gemini remains the LLM, but its tools come from the shared MCP tool
 * registry via {@link GeminiToolBridge} — the same tools an external client (Claude Desktop) sees.
 * The acting user/workspace is published to {@link McpPrincipalContext} for the duration of the
 * loop so tools resolve scope from the credential, never from model-supplied arguments.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private static final int MAX_TOOL_CALLS = 3;
    private static final String MODEL_ID = "gemini-2.5-flash";
    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");

    private static final Map<String, Locale> LOCALE_MAP = Map.of(
            "pt-BR", Locale.of("pt", "BR"),
            "en-US", Locale.of("en", "US"),
            "es", Locale.of("es")
    );

    private final StageRepository stageRepository;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;
    private final GeminiToolBridge geminiToolBridge;

    @Transactional
    public ChatResponse chat(String apiKey, List<Map<String, String>> historyInput, Integer userId, Integer workspaceId, String language) {
        McpPrincipalContext.set(userId, workspaceId);
        try {
            Client client = Client.builder().apiKey(apiKey).build();
            List<Map<String, String>> history = new ArrayList<>(historyInput);

            List<Content> contents = buildContents(history);
            GenerateContentConfig config = buildConfig(language, workspaceId);

            GenerateContentResponse response = client.models.generateContent(
                    MODEL_ID, contents, config);

            int toolCallCount = 0;
            boolean tasksCreatedInSession = false;

            while (response.functionCalls() != null
                    && !response.functionCalls().isEmpty()
                    && toolCallCount < MAX_TOOL_CALLS) {

                // Model turn with tool calls
                Content modelContent = response.candidates()
                        .flatMap(c -> c.isEmpty() ? Optional.empty() : c.get(0).content())
                        .orElseThrow();

                contents.add(modelContent);
                addContentToHistory(history, modelContent);

                List<Part> functionResponseParts = new ArrayList<>();
                for (FunctionCall fc : response.functionCalls()) {
                    String fnName = fc.name().orElse("");
                    Map<String, Object> fnArgs = fc.args().orElse(Map.of());
                    Map<String, Object> result = geminiToolBridge.dispatch(fnName, fnArgs);
                    if ("create_task".equals(fnName) && Boolean.TRUE.equals(result.get("success"))) {
                        tasksCreatedInSession = true;
                    }
                    functionResponseParts.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .name(fnName)
                                    .response(result)
                                    .build())
                            .build());
                }
                toolCallCount++;

                // User turn with tool responses
                Content userContent = Content.builder()
                        .role("user")
                        .parts(functionResponseParts)
                        .build();

                contents.add(userContent);
                addContentToHistory(history, userContent);

                response = client.models.generateContent(MODEL_ID, contents, config);
            }

            // Final model turn (the text response)
            Content finalContent = response.candidates()
                    .flatMap(c -> c.isEmpty() ? Optional.empty() : c.get(0).content())
                    .orElse(null);

            if (finalContent != null) {
                addContentToHistory(history, finalContent);
            }

            String reply = response.text() != null ? response.text() : messageService.get("error.gemini.no_response");
            return new ChatResponse(reply, history, tasksCreatedInSession);

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with Gemini", e);
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("429") || msg.contains("quota") || msg.contains("rate limit") || msg.contains("resource_exhausted")) {
                throw new BadRequestException(messageService.get("error.gemini.rate_limit"));
            }
            if (msg.contains("api key") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401")) {
                throw new BadRequestException(messageService.get("error.gemini.key.invalid"));
            }
            throw new BadRequestException(messageService.get("error.gemini.api"));
        } finally {
            McpPrincipalContext.clear();
        }
    }

    private void addContentToHistory(List<Map<String, String>> history, Content content) {
        Map<String, String> entry = new HashMap<>();
        entry.put("role", content.role().orElse("user"));

        List<Part> parts = content.parts().orElse(List.of());

        try {
            // We store the raw JSON of parts to reconstruct it later
            entry.put("parts", objectMapper.writeValueAsString(parts));
            // Keep 'text' for backward compatibility or simple display if needed
            parts.stream()
                    .filter(p -> p.text().isPresent())
                    .findFirst()
                    .ifPresent(p -> entry.put("text", p.text().get()));
        } catch (Exception e) {
            log.error("Error serializing content parts", e);
        }

        history.add(entry);
    }

    private List<Content> buildContents(List<Map<String, String>> history) {
        List<Content> contents = new ArrayList<>();
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String partsJson = msg.get("parts");
            String text = msg.get("text");

            if (partsJson != null) {
                try {
                    Part[] partsArray = objectMapper.readValue(partsJson, Part[].class);
                    contents.add(Content.builder()
                            .role(role)
                            .parts(Arrays.asList(partsArray))
                            .build());
                    continue;
                } catch (Exception e) {
                    log.error("Error deserializing content parts", e);
                }
            }

            if (role != null && text != null) {
                contents.add(Content.builder()
                        .role(role.equals("model") ? "model" : "user")
                        .parts(List.of(Part.builder().text(text).build()))
                        .build());
            }
        }
        return contents;
    }

    private GenerateContentConfig buildConfig(String language, Integer workspaceId) {
        Locale locale = LOCALE_MAP.getOrDefault(language, Locale.of("pt", "BR"));
        LocalDate today = LocalDate.now(ZONE_BR);
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);

        List<String> stageNames = stageNames(workspaceId);
        String stageList = String.join(", ", stageNames);
        String systemPrompt = messageService.get("ai.system_prompt", dateStr, dayOfWeek)
                + "\n\n" + messageService.get("ai.stages_note", stageList);

        Tool tools = Tool.builder()
                .functionDeclarations(geminiToolBridge.buildFunctionDeclarations(stageNames))
                .build();

        return GenerateContentConfig.builder()
                .systemInstruction(Content.builder()
                        .parts(List.of(Part.builder().text(systemPrompt).build()))
                        .build())
                .tools(List.of(tools))
                .automaticFunctionCalling(
                        AutomaticFunctionCallingConfig.builder()
                                .disable(true)
                                .build())
                .build();
    }

    private List<String> stageNames(Integer workspaceId) {
        return stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId)
                .stream().map(Stage::getName).toList();
    }
}
