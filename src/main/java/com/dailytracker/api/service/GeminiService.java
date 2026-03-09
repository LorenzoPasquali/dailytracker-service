package com.dailytracker.api.service;

import com.dailytracker.api.dto.response.ChatResponse;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.entity.User;
import com.dailytracker.api.exception.BadRequestException;
import com.dailytracker.api.i18n.MessageService;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import com.dailytracker.api.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    private static final Map<String, Locale> LOCALE_MAP = Map.of(
            "pt-BR", Locale.of("pt", "BR"),
            "en-US", Locale.of("en", "US"),
            "es", Locale.of("es")
    );

    private final TaskRepository taskRepository;
    private final ProjectRepository projectRepository;
    private final TaskTypeRepository taskTypeRepository;
    private final UserRepository userRepository;
    private final MessageService messageService;
    private final ObjectMapper objectMapper;

    @Transactional
    public ChatResponse chat(String apiKey, List<Map<String, String>> historyInput, Integer userId, String language) {
        try {
            Client client = Client.builder().apiKey(apiKey).build();
            List<Map<String, String>> history = new ArrayList<>(historyInput);

            List<Content> contents = buildContents(history);
            GenerateContentConfig config = buildConfig(language);

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
                    Map<String, Object> result = executeTool(fnName, fnArgs, userId, language);
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

    private GenerateContentConfig buildConfig(String language) {
        Locale locale = LOCALE_MAP.getOrDefault(language, Locale.of("pt", "BR"));
        LocalDate today = LocalDate.now(ZONE_BR);
        String dateStr = today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        String dayOfWeek = today.getDayOfWeek().getDisplayName(TextStyle.FULL, locale);
        String systemPrompt = messageService.get("ai.system_prompt", dateStr, dayOfWeek);

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
                .description("Returns the user's task types. If projectName is provided, returns only types for that project.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .properties(Map.of(
                                "projectName", Schema.builder()
                                        .type("STRING")
                                        .description("Filter task types by exact project name.")
                                        .build()
                        ))
                        .build())
                .build();

        FunctionDeclaration createTask = FunctionDeclaration.builder()
                .name("create_task")
                .description("Creates a new task for the user. Call this only after collecting all required information from the user.")
                .parameters(Schema.builder()
                        .type("OBJECT")
                        .required(List.of("title"))
                        .properties(Map.of(
                                "title", Schema.builder()
                                        .type("STRING")
                                        .description("Title of the task.")
                                        .build(),
                                "description", Schema.builder()
                                        .type("STRING")
                                        .description("Optional description of the task.")
                                        .build(),
                                "status", Schema.builder()
                                        .type("STRING")
                                        .description("Initial status of the task.")
                                        .enum_(List.of("PLANNED", "DOING", "DONE"))
                                        .build(),
                                "projectName", Schema.builder()
                                        .type("STRING")
                                        .description("Exact name of the project to associate the task with.")
                                        .build(),
                                "taskTypeName", Schema.builder()
                                        .type("STRING")
                                        .description("Exact name of the task type to associate the task with.")
                                        .build()
                        ))
                        .build())
                .build();

        return Tool.builder()
                .functionDeclarations(List.of(getTasks, getProjects, getTaskTypes, createTask))
                .build();
    }

    private String translateStatus(String status, String language) {
        if (status == null) return "?";
        return messageService.get("ai.status." + status);
    }

    private Map<String, Object> executeTool(String functionName, Map<String, Object> args, Integer userId, String language) {
        String noProject = messageService.get("ai.no_project");
        String noType = messageService.get("ai.no_type");

        return switch (functionName) {
            case "get_tasks" -> {
                List<Task> tasks = taskRepository.findByUserIdOrdered(userId);
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

                String projectFilter = args.get("projectName") != null ? args.get("projectName").toString() : null;
                if (projectFilter != null) {
                    taskTypes = taskTypes.stream()
                            .filter(tt -> projectNames.getOrDefault(tt.getProjectId(), "")
                                    .equalsIgnoreCase(projectFilter))
                            .toList();
                }

                List<Map<String, Object>> typeList = taskTypes.stream().map(tt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", tt.getName());
                    m.put("project", projectNames.getOrDefault(tt.getProjectId(), noProject));
                    return m;
                }).toList();
                yield Map.of("taskTypes", typeList);
            }
            case "create_task" -> {
                String title = args.get("title") != null ? args.get("title").toString() : null;
                if (title == null || title.isBlank()) {
                    yield Map.of("error", "title is required");
                }

                String description = args.get("description") != null ? args.get("description").toString() : null;
                String status = args.get("status") != null ? args.get("status").toString() : "PLANNED";
                String projectName = args.get("projectName") != null ? args.get("projectName").toString() : null;
                String taskTypeName = args.get("taskTypeName") != null ? args.get("taskTypeName").toString() : null;

                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new RuntimeException("User not found"));

                Task task = Task.builder()
                        .title(title)
                        .description(description)
                        .status(status)
                        .priority("MEDIUM")
                        .user(user)
                        .build();

                boolean projectNotFound = false;
                if (projectName != null) {
                    Optional<Project> matchedProject = projectRepository.findByUserIdOrderByNameAsc(userId).stream()
                            .filter(p -> p.getName().equalsIgnoreCase(projectName))
                            .findFirst();
                    if (matchedProject.isPresent()) {
                        task.setProject(matchedProject.get());
                    } else {
                        projectNotFound = true;
                    }
                }

                boolean typeNotFound = false;
                if (taskTypeName != null) {
                    Optional<TaskType> matchedType = taskTypeRepository.findByProject_UserIdOrderByNameAsc(userId).stream()
                            .filter(tt -> tt.getName().equalsIgnoreCase(taskTypeName))
                            .findFirst();
                    if (matchedType.isPresent()) {
                        task.setTaskType(matchedType.get());
                    } else {
                        typeNotFound = true;
                    }
                }

                Task saved = taskRepository.save(task);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("success", true);
                result.put("title", saved.getTitle());
                result.put("status", translateStatus(saved.getStatus(), language));
                if (projectNotFound) result.put("projectNotFound", true);
                if (typeNotFound) result.put("typeNotFound", true);
                yield result;
            }
            default -> Map.of("error", messageService.get("error.gemini.unknown_tool", functionName));
        };
    }
}
