package com.dailytracker.api.service;

import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import com.google.genai.Client;
import com.google.genai.types.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private static final int MAX_TOOL_CALLS = 3;
    private static final String MODEL_ID = "gemini-2.5-flash";

    private static final String SYSTEM_PROMPT = """
            Você é um assistente especializado em metodologias ágeis e Scrum para o DailyTracker.
            Seu papel é analisar as tarefas, projetos e tipos de tarefa do usuário e fornecer
            insights sobre produtividade, gargalos no fluxo Kanban (PLANNED/DOING/DONE),
            distribuição de trabalho e sugestões de melhoria de processo.

            Você tem acesso a ferramentas para consultar as tarefas, projetos e tipos de tarefa
            do usuário. Use-as quando necessário para responder perguntas sobre os dados do usuário.

            Responda sempre em português do Brasil. Seja conciso e orientado a dados.
            Use formatação markdown quando apropriado para melhorar a legibilidade.
            """;

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TaskTypeRepository taskTypeRepository;

    @Transactional(readOnly = true)
    public String chat(String apiKey, List<Map<String, String>> history, Integer userId) {
        try {
            Client client = Client.builder().apiKey(apiKey).build();

            List<Content> contents = buildContents(history);
            GenerateContentConfig config = buildConfig();

            GenerateContentResponse response = client.models.generateContent(
                    MODEL_ID, contents, config);

            int toolCallCount = 0;

            while (response.functionCalls() != null
                    && !response.functionCalls().isEmpty()
                    && toolCallCount < MAX_TOOL_CALLS) {

                response.candidates()
                        .flatMap(c -> c.isEmpty() ? Optional.empty() : c.get(0).content())
                        .ifPresent(contents::add);

                List<Part> functionResponseParts = new ArrayList<>();
                for (FunctionCall fc : response.functionCalls()) {
                    String fnName = fc.name().orElse("");
                    Map<String, Object> fnArgs = fc.args().orElse(Map.of());
                    Map<String, Object> result = executeTool(fnName, fnArgs, userId);
                    functionResponseParts.add(Part.builder()
                            .functionResponse(FunctionResponse.builder()
                                    .name(fnName)
                                    .response(result)
                                    .build())
                            .build());
                }
                toolCallCount++;

                contents.add(Content.builder()
                        .role("user")
                        .parts(functionResponseParts)
                        .build());

                response = client.models.generateContent(MODEL_ID, contents, config);
            }

            return response.text() != null ? response.text() : "Não consegui gerar uma resposta.";

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Erro ao comunicar com Gemini", e);
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("api key") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401")) {
                throw new BadRequestException("Chave Gemini inválida ou expirada. Verifique suas configurações.");
            }
            throw new BadRequestException("Erro ao comunicar com a IA. Tente novamente mais tarde.");
        }
    }

    private List<Content> buildContents(List<Map<String, String>> history) {
        List<Content> contents = new ArrayList<>();
        for (Map<String, String> msg : history) {
            String role = msg.get("role");
            String text = msg.get("text");
            if (role != null && text != null) {
                contents.add(Content.builder()
                        .role(role.equals("model") ? "model" : "user")
                        .parts(List.of(Part.builder().text(text).build()))
                        .build());
            }
        }
        return contents;
    }

    private GenerateContentConfig buildConfig() {
        return GenerateContentConfig.builder()
                .systemInstruction(Content.builder()
                        .parts(List.of(Part.builder().text(SYSTEM_PROMPT).build()))
                        .build())
                .tools(List.of(buildTools()))
                .automaticFunctionCalling(
                        AutomaticFunctionCallingConfig.builder()
                                .disable(true)
                                .build())
                .build();
    }

    private Tool buildTools() {
        FunctionDeclaration getTasks = FunctionDeclaration.builder()
                .name("get_tasks")
                .description("Retorna todas as tarefas do usuário com título, descrição, status (PLANNED/DOING/DONE), datas de criação/atualização, projeto e tipo de tarefa.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of())
                        .build())
                .build();

        FunctionDeclaration getProjects = FunctionDeclaration.builder()
                .name("get_projects")
                .description("Retorna todos os projetos do usuário com nome e cor.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of())
                        .build())
                .build();

        FunctionDeclaration getTaskTypes = FunctionDeclaration.builder()
                .name("get_task_types")
                .description("Retorna todos os tipos de tarefa do usuário com nome e projeto associado.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of())
                        .build())
                .build();

        return Tool.builder()
                .functionDeclarations(List.of(getTasks, getProjects, getTaskTypes))
                .build();
    }

    private Map<String, Object> executeTool(String functionName, Map<String, Object> args, Integer userId) {
        return switch (functionName) {
            case "get_tasks" -> {
                List<Task> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
                List<Map<String, Object>> taskList = tasks.stream().map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("title", t.getTitle());
                    m.put("description", t.getDescription() != null ? t.getDescription() : "");
                    m.put("status", t.getStatus());
                    m.put("createdAt", t.getCreatedAt().toString());
                    m.put("updatedAt", t.getUpdatedAt().toString());
                    m.put("projectId", t.getProjectId());
                    m.put("taskTypeId", t.getTaskTypeId());
                    return m;
                }).toList();
                yield Map.of("tasks", taskList);
            }
            case "get_projects" -> {
                List<Project> projects = projectRepository.findByUserIdOrderByNameAsc(userId);
                List<Map<String, Object>> projectList = projects.stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", p.getId());
                    m.put("name", p.getName());
                    m.put("color", p.getColor());
                    return m;
                }).toList();
                yield Map.of("projects", projectList);
            }
            case "get_task_types" -> {
                List<TaskType> taskTypes = taskTypeRepository.findByProject_UserIdOrderByNameAsc(userId);
                List<Map<String, Object>> typeList = taskTypes.stream().map(tt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", tt.getId());
                    m.put("name", tt.getName());
                    m.put("projectId", tt.getProjectId());
                    return m;
                }).toList();
                yield Map.of("taskTypes", typeList);
            }
            default -> Map.of("error", "Ferramenta desconhecida: " + functionName);
        };
    }
}
