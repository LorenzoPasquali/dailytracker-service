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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GeminiService {

    private static final int MAX_TOOL_CALLS = 3;
    private static final String MODEL_ID = "gemini-2.5-flash";
    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");

    private static final String SYSTEM_PROMPT_TEMPLATE = """
            Você é o assistente de IA do DailyTracker, um sistema de gerenciamento de tarefas e projetos
            com quadro Kanban. O sistema organiza tarefas em três colunas:
            - Planejado (PLANNED) — tarefas planejadas, ainda não iniciadas
            - Em Progresso (DOING) — tarefas sendo trabalhadas atualmente
            - Feito (DONE) — tarefas concluídas

            Cada tarefa pode pertencer a um projeto e ter um tipo de tarefa associado.

            Data e hora atual: %s (%s).

            Seu papel é ajudar o usuário a:
            - Preparar resumos para daily scrums — quando pedido, gere um resumo curto e direto
              do que foi feito, o que está em progresso e o que está planejado para o período solicitado.
              Use bullet points curtos sem texto desnecessário.
            - Entender o estado atual das suas tarefas e projetos
            - Identificar gargalos e tarefas paradas há muito tempo
            - Analisar produtividade e distribuição de trabalho entre projetos
            - Sugerir melhorias no fluxo de trabalho

            Você tem acesso a ferramentas para consultar as tarefas, projetos e tipos de tarefa
            do usuário. A ferramenta get_tasks aceita filtros opcionais de data e status — use-os
            para buscar apenas os dados relevantes à pergunta do usuário.

            Quando o usuário pedir resumo de um dia específico (ex: "sexta-feira", "ontem", "dia 5"),
            calcule a data correta com base na data atual e use os filtros de data da ferramenta.

            Vocabulário do usuário e mapeamento de status:
            - "pendentes", "pendente" = tarefas com status Planejado (PLANNED), ou seja, ainda não iniciadas
            - "em andamento", "em progresso", "fazendo" = tarefas com status Em Progresso (DOING)
            - "concluídas", "feitas", "finalizadas", "prontas" = tarefas com status Feito (DONE)
            Sempre respeite esse mapeamento ao filtrar por status. Se o usuário pedir "tarefas pendentes",
            busque apenas PLANNED. Se pedir "tarefas não concluídas", busque PLANNED e DOING separadamente.

            Regras:
            - Nunca mencione IDs internos. Use sempre nomes e títulos.
            - Responda sempre em português do Brasil.
            - Seja conciso e orientado a dados. Resumos de daily devem ser curtos.
            - Use formatação markdown quando apropriado.
            - Ao exibir status, use os nomes traduzidos: Planejado, Em Progresso, Feito.
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
        LocalDate today = LocalDate.now(ZONE_BR);
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.of("pt", "BR"));
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, dateStr, dayOfWeek);

        return GenerateContentConfig.builder()
                .systemInstruction(Content.builder()
                        .parts(List.of(Part.builder().text(systemPrompt).build()))
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
                .description("Retorna as tarefas do usuário. Pode filtrar por data e/ou status. " +
                        "Para resumos de daily, use os filtros de data para buscar tarefas de dias específicos.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                                "date", Schema.builder()
                                        .type("STRING")
                                        .description("Filtrar tarefas de uma data específica no formato YYYY-MM-DD. " +
                                                "Retorna tarefas criadas ou atualizadas nesse dia.")
                                        .build(),
                                "startDate", Schema.builder()
                                        .type("STRING")
                                        .description("Data inicial do período no formato YYYY-MM-DD. Usar junto com endDate.")
                                        .build(),
                                "endDate", Schema.builder()
                                        .type("STRING")
                                        .description("Data final do período no formato YYYY-MM-DD. Usar junto com startDate.")
                                        .build(),
                                "status", Schema.builder()
                                        .type("STRING")
                                        .description("Filtrar por status: PLANNED, DOING ou DONE.")
                                        .enum_(List.of("PLANNED", "DOING", "DONE"))
                                        .build(),
                                "project", Schema.builder()
                                        .type("STRING")
                                        .description("Filtrar por nome do projeto.")
                                        .build()
                        ))
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

    private String translateStatus(String status) {
        if (status == null) return "Desconhecido";
        return switch (status) {
            case "PLANNED" -> "Planejado";
            case "DOING" -> "Em Progresso";
            case "DONE" -> "Feito";
            default -> status;
        };
    }

    private Map<String, Object> executeTool(String functionName, Map<String, Object> args, Integer userId) {
        return switch (functionName) {
            case "get_tasks" -> {
                List<Task> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
                Map<Integer, String> projectNames = projectRepository.findByUserIdOrderByNameAsc(userId)
                        .stream().collect(Collectors.toMap(Project::getId, Project::getName));
                Map<Integer, String> taskTypeNames = taskTypeRepository.findByProject_UserIdOrderByNameAsc(userId)
                        .stream().collect(Collectors.toMap(TaskType::getId, TaskType::getName));

                // Apply date filter
                String date = args.get("date") != null ? args.get("date").toString() : null;
                String startDate = args.get("startDate") != null ? args.get("startDate").toString() : null;
                String endDate = args.get("endDate") != null ? args.get("endDate").toString() : null;
                String statusFilter = args.get("status") != null ? args.get("status").toString() : null;
                String projectFilter = args.get("project") != null ? args.get("project").toString() : null;

                if (date != null) {
                    LocalDate ld = LocalDate.parse(date);
                    Instant dayStart = ld.atStartOfDay(ZONE_BR).toInstant();
                    Instant dayEnd = ld.plusDays(1).atStartOfDay(ZONE_BR).toInstant();
                    tasks = tasks.stream().filter(t ->
                            (t.getCreatedAt().compareTo(dayStart) >= 0 && t.getCreatedAt().isBefore(dayEnd)) ||
                            (t.getUpdatedAt().compareTo(dayStart) >= 0 && t.getUpdatedAt().isBefore(dayEnd))
                    ).toList();
                } else if (startDate != null && endDate != null) {
                    Instant rangeStart = LocalDate.parse(startDate).atStartOfDay(ZONE_BR).toInstant();
                    Instant rangeEnd = LocalDate.parse(endDate).plusDays(1).atStartOfDay(ZONE_BR).toInstant();
                    tasks = tasks.stream().filter(t ->
                            (t.getCreatedAt().compareTo(rangeStart) >= 0 && t.getCreatedAt().isBefore(rangeEnd)) ||
                            (t.getUpdatedAt().compareTo(rangeStart) >= 0 && t.getUpdatedAt().isBefore(rangeEnd))
                    ).toList();
                }

                if (statusFilter != null) {
                    tasks = tasks.stream().filter(t -> statusFilter.equals(t.getStatus())).toList();
                }

                if (projectFilter != null) {
                    tasks = tasks.stream().filter(t -> {
                        String pName = t.getProjectId() != null ? projectNames.get(t.getProjectId()) : null;
                        return pName != null && pName.toLowerCase().contains(projectFilter.toLowerCase());
                    }).toList();
                }

                List<Map<String, Object>> taskList = tasks.stream().map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("title", t.getTitle());
                    m.put("description", t.getDescription() != null ? t.getDescription() : "");
                    m.put("status", translateStatus(t.getStatus()));
                    m.put("createdAt", t.getCreatedAt().toString());
                    m.put("updatedAt", t.getUpdatedAt().toString());
                    m.put("project", t.getProjectId() != null ? projectNames.getOrDefault(t.getProjectId(), "Sem projeto") : "Sem projeto");
                    m.put("taskType", t.getTaskTypeId() != null ? taskTypeNames.getOrDefault(t.getTaskTypeId(), "Sem tipo") : "Sem tipo");
                    return m;
                }).toList();
                yield Map.of("tasks", taskList, "count", taskList.size());
            }
            case "get_projects" -> {
                List<Project> projects = projectRepository.findByUserIdOrderByNameAsc(userId);
                List<Map<String, Object>> projectList = projects.stream().map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", p.getName());
                    m.put("color", p.getColor());
                    return m;
                }).toList();
                yield Map.of("projects", projectList);
            }
            case "get_task_types" -> {
                List<TaskType> taskTypes = taskTypeRepository.findByProject_UserIdOrderByNameAsc(userId);
                Map<Integer, String> projectNames = projectRepository.findByUserIdOrderByNameAsc(userId)
                        .stream().collect(Collectors.toMap(Project::getId, Project::getName));

                List<Map<String, Object>> typeList = taskTypes.stream().map(tt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", tt.getName());
                    m.put("project", projectNames.getOrDefault(tt.getProjectId(), "Sem projeto"));
                    return m;
                }).toList();
                yield Map.of("taskTypes", typeList);
            }
            default -> Map.of("error", "Ferramenta desconhecida: " + functionName);
        };
    }
}
