package com.dailytracker.api.service;

import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.i18n.MessageService;
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

    private static final Map<String, String> SYSTEM_PROMPTS = Map.of(
            "pt-BR", """
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
                    """,
            "en-US", """
                    You are the AI assistant for DailyTracker, a task and project management system
                    with a Kanban board. The system organizes tasks into three columns:
                    - Planned (PLANNED) — planned tasks, not yet started
                    - In Progress (DOING) — tasks currently being worked on
                    - Done (DONE) — completed tasks

                    Each task can belong to a project and have an associated task type.

                    Current date and time: %s (%s).

                    Your role is to help the user:
                    - Prepare daily scrum summaries — when asked, generate a short and direct summary
                      of what was done, what is in progress, and what is planned for the requested period.
                      Use short bullet points without unnecessary text.
                    - Understand the current state of their tasks and projects
                    - Identify bottlenecks and tasks that have been stalled for too long
                    - Analyze productivity and work distribution across projects
                    - Suggest workflow improvements

                    You have access to tools to query the user's tasks, projects, and task types.
                    The get_tasks tool accepts optional date and status filters — use them
                    to fetch only the data relevant to the user's question.

                    When the user asks for a summary of a specific day (e.g., "Friday", "yesterday", "the 5th"),
                    calculate the correct date based on the current date and use the tool's date filters.

                    User vocabulary and status mapping:
                    - "pending", "to do" = tasks with Planned (PLANNED) status, i.e., not yet started
                    - "in progress", "ongoing", "doing" = tasks with In Progress (DOING) status
                    - "completed", "done", "finished" = tasks with Done (DONE) status
                    Always respect this mapping when filtering by status.

                    Rules:
                    - Never mention internal IDs. Always use names and titles.
                    - Always respond in English.
                    - Be concise and data-oriented. Daily summaries should be short.
                    - Use markdown formatting when appropriate.
                    - When displaying status, use the translated names: Planned, In Progress, Done.
                    """,
            "es", """
                    Eres el asistente de IA de DailyTracker, un sistema de gestión de tareas y proyectos
                    con tablero Kanban. El sistema organiza las tareas en tres columnas:
                    - Planificado (PLANNED) — tareas planificadas, aún no iniciadas
                    - En Progreso (DOING) — tareas en las que se está trabajando actualmente
                    - Hecho (DONE) — tareas completadas

                    Cada tarea puede pertenecer a un proyecto y tener un tipo de tarea asociado.

                    Fecha y hora actual: %s (%s).

                    Tu rol es ayudar al usuario a:
                    - Preparar resúmenes para daily scrums — cuando se solicite, genera un resumen corto y directo
                      de lo que se hizo, lo que está en progreso y lo que está planificado para el período solicitado.
                      Usa puntos breves sin texto innecesario.
                    - Entender el estado actual de sus tareas y proyectos
                    - Identificar cuellos de botella y tareas estancadas por mucho tiempo
                    - Analizar productividad y distribución del trabajo entre proyectos
                    - Sugerir mejoras en el flujo de trabajo

                    Tienes acceso a herramientas para consultar las tareas, proyectos y tipos de tarea
                    del usuario. La herramienta get_tasks acepta filtros opcionales de fecha y estado — úsalos
                    para buscar solo los datos relevantes a la pregunta del usuario.

                    Cuando el usuario pida un resumen de un día específico (ej: "viernes", "ayer", "el día 5"),
                    calcula la fecha correcta basándote en la fecha actual y usa los filtros de fecha de la herramienta.

                    Vocabulario del usuario y mapeo de estados:
                    - "pendientes", "por hacer" = tareas con estado Planificado (PLANNED), es decir, aún no iniciadas
                    - "en progreso", "en curso", "haciendo" = tareas con estado En Progreso (DOING)
                    - "completadas", "hechas", "finalizadas", "listas" = tareas con estado Hecho (DONE)
                    Siempre respeta este mapeo al filtrar por estado.

                    Reglas:
                    - Nunca menciones IDs internos. Usa siempre nombres y títulos.
                    - Responde siempre en español.
                    - Sé conciso y orientado a datos. Los resúmenes de daily deben ser cortos.
                    - Usa formato markdown cuando sea apropiado.
                    - Al mostrar estados, usa los nombres traducidos: Planificado, En Progreso, Hecho.
                    """
    );

    private static final Map<String, Map<String, String>> STATUS_LABELS = Map.of(
            "pt-BR", Map.of("PLANNED", "Planejado", "DOING", "Em Progresso", "DONE", "Feito"),
            "en-US", Map.of("PLANNED", "Planned", "DOING", "In Progress", "DONE", "Done"),
            "es", Map.of("PLANNED", "Planificado", "DOING", "En Progreso", "DONE", "Hecho")
    );

    private static final Map<String, String> NO_PROJECT_LABELS = Map.of(
            "pt-BR", "Sem projeto", "en-US", "No project", "es", "Sin proyecto"
    );

    private static final Map<String, String> NO_TYPE_LABELS = Map.of(
            "pt-BR", "Sem tipo", "en-US", "No type", "es", "Sin tipo"
    );

    private static final Map<String, Locale> LOCALE_MAP = Map.of(
            "pt-BR", Locale.of("pt", "BR"),
            "en-US", Locale.of("en", "US"),
            "es", Locale.of("es")
    );

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TaskTypeRepository taskTypeRepository;
    private final MessageService messageService;

    @Transactional(readOnly = true)
    public String chat(String apiKey, List<Map<String, String>> history, Integer userId, String language) {
        try {
            Client client = Client.builder().apiKey(apiKey).build();

            List<Content> contents = buildContents(history);
            GenerateContentConfig config = buildConfig(language);

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
                    Map<String, Object> result = executeTool(fnName, fnArgs, userId, language);
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

            return response.text() != null ? response.text() : messageService.get("error.gemini.no_response");

        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error communicating with Gemini", e);
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("api key") || msg.contains("unauthorized") || msg.contains("403") || msg.contains("401")) {
                throw new BadRequestException(messageService.get("error.gemini.key.invalid"));
            }
            throw new BadRequestException(messageService.get("error.gemini.api"));
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

    private GenerateContentConfig buildConfig(String language) {
        Locale locale = LOCALE_MAP.getOrDefault(language, Locale.of("pt", "BR"));
        LocalDate today = LocalDate.now(ZONE_BR);
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
        String promptTemplate = SYSTEM_PROMPTS.getOrDefault(language, SYSTEM_PROMPTS.get("pt-BR"));
        String systemPrompt = String.format(promptTemplate, dateStr, dayOfWeek);

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
                .description("Returns the user's tasks. Can filter by date and/or status. " +
                        "For daily summaries, use date filters to fetch tasks from specific days.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                                "date", Schema.builder()
                                        .type("STRING")
                                        .description("Filter tasks for a specific date in YYYY-MM-DD format.")
                                        .build(),
                                "startDate", Schema.builder()
                                        .type("STRING")
                                        .description("Start date of the period in YYYY-MM-DD format. Use with endDate.")
                                        .build(),
                                "endDate", Schema.builder()
                                        .type("STRING")
                                        .description("End date of the period in YYYY-MM-DD format. Use with startDate.")
                                        .build(),
                                "status", Schema.builder()
                                        .type("STRING")
                                        .description("Filter by status: PLANNED, DOING or DONE.")
                                        .enum_(List.of("PLANNED", "DOING", "DONE"))
                                        .build(),
                                "project", Schema.builder()
                                        .type("STRING")
                                        .description("Filter by project name.")
                                        .build()
                        ))
                        .build())
                .build();

        FunctionDeclaration getProjects = FunctionDeclaration.builder()
                .name("get_projects")
                .description("Returns all user's projects with name and color.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of())
                        .build())
                .build();

        FunctionDeclaration getTaskTypes = FunctionDeclaration.builder()
                .name("get_task_types")
                .description("Returns all user's task types with name and associated project.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of())
                        .build())
                .build();

        return Tool.builder()
                .functionDeclarations(List.of(getTasks, getProjects, getTaskTypes))
                .build();
    }

    private String translateStatus(String status, String language) {
        if (status == null) return "?";
        Map<String, String> labels = STATUS_LABELS.getOrDefault(language, STATUS_LABELS.get("pt-BR"));
        return labels.getOrDefault(status, status);
    }

    private Map<String, Object> executeTool(String functionName, Map<String, Object> args, Integer userId, String language) {
        String noProject = NO_PROJECT_LABELS.getOrDefault(language, NO_PROJECT_LABELS.get("pt-BR"));
        String noType = NO_TYPE_LABELS.getOrDefault(language, NO_TYPE_LABELS.get("pt-BR"));

        return switch (functionName) {
            case "get_tasks" -> {
                List<Task> tasks = taskRepository.findByUserIdOrderByCreatedAtDesc(userId);
                Map<Integer, String> projectNames = projectRepository.findByUserIdOrderByNameAsc(userId)
                        .stream().collect(Collectors.toMap(Project::getId, Project::getName));
                Map<Integer, String> taskTypeNames = taskTypeRepository.findByProject_UserIdOrderByNameAsc(userId)
                        .stream().collect(Collectors.toMap(TaskType::getId, TaskType::getName));

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
                    m.put("status", translateStatus(t.getStatus(), language));
                    m.put("createdAt", t.getCreatedAt().toString());
                    m.put("updatedAt", t.getUpdatedAt().toString());
                    m.put("project", t.getProjectId() != null ? projectNames.getOrDefault(t.getProjectId(), noProject) : noProject);
                    m.put("taskType", t.getTaskTypeId() != null ? taskTypeNames.getOrDefault(t.getTaskTypeId(), noType) : noType);
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
                    m.put("project", projectNames.getOrDefault(tt.getProjectId(), noProject));
                    return m;
                }).toList();
                yield Map.of("taskTypes", typeList);
            }
            default -> Map.of("error", messageService.get("error.gemini.unknown_tool", functionName));
        };
    }
}
