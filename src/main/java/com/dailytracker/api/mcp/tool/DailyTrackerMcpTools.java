package com.dailytracker.api.mcp.tool;

import com.dailytracker.api.dto.request.TaskRequest;
import com.dailytracker.api.dto.request.TaskUpdateRequest;
import com.dailytracker.api.entity.Project;
import com.dailytracker.api.entity.Stage;
import com.dailytracker.api.entity.Task;
import com.dailytracker.api.entity.TaskType;
import com.dailytracker.api.mcp.McpPrincipalContext;
import com.dailytracker.api.repository.ProjectRepository;
import com.dailytracker.api.repository.StageRepository;
import com.dailytracker.api.repository.TaskRepository;
import com.dailytracker.api.repository.TaskTypeRepository;
import com.dailytracker.api.service.ProjectService;
import com.dailytracker.api.service.StageService;
import com.dailytracker.api.service.TaskService;
import com.dailytracker.api.service.WorkspaceService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The single source of truth for the AI tool surface.
 *
 * <p>These {@code @Tool} methods are registered once and consumed by two clients: the internal
 * Gemini path (in-process, via {@code GeminiToolBridge}) and external MCP clients such as Claude
 * Desktop (over HTTP, Fase 2). Each tool resolves the acting user from {@link McpPrincipalContext}
 * — never from an argument — and re-checks workspace membership, so data never leaks across users.
 *
 * <p>Writes are routed through {@link TaskService} so WebSocket events and notification scheduling
 * fire exactly as they do for the REST API.
 */
@Component
@RequiredArgsConstructor
public class DailyTrackerMcpTools {

    private static final ZoneId ZONE_BR = ZoneId.of("America/Sao_Paulo");
    private static final Set<String> VALID_PRIORITIES = Set.of("HIGH", "MEDIUM", "LOW");

    private final WorkspaceService workspaceService;
    private final StageService stageService;
    private final ProjectService projectService;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final StageRepository stageRepository;
    private final ProjectRepository projectRepository;
    private final TaskTypeRepository taskTypeRepository;

    // ── Read tools ────────────────────────────────────────────────────────────

    @Tool(name = "list_workspaces",
            description = "Lista os workspaces do usuário (id, nome, se é pessoal). Use para descobrir em qual workspace operar quando houver dúvida.")
    public List<Map<String, Object>> listWorkspaces() {
        return workspaceService.findAllForUser(userId());
    }

    @Tool(name = "list_stages",
            description = "Lista as etapas (colunas) do quadro Kanban na ordem, com nome, posição e se é etapa final (concluído). Use os nomes exatos retornados ao filtrar ou definir status de tarefas.")
    public List<Map<String, Object>> listStages(
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        return stageService.list(uid, ws);
    }

    @Tool(name = "get_tasks",
            description = "Retorna as tarefas do usuário com contagem por etapa. Filtre por data, período, etapa (status) ou projeto para buscar apenas o relevante à pergunta — não despeje tudo sem necessidade.")
    public Map<String, Object> getTasks(
            @ToolParam(required = false, description = "Filtra tarefas de uma data específica no formato YYYY-MM-DD.") String date,
            @ToolParam(required = false, description = "Início do período YYYY-MM-DD (use junto com endDate).") String startDate,
            @ToolParam(required = false, description = "Fim do período YYYY-MM-DD (use junto com startDate).") String endDate,
            @ToolParam(required = false, description = "Nome exato da etapa (coluna) para filtrar.") String status,
            @ToolParam(required = false, description = "Nome do projeto para filtrar.") String project,
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);

        Map<Integer, String> projectNames = projectRepository.findByWorkspaceIdOrderByNameAsc(ws)
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));
        Map<Integer, String> taskTypeNames = taskTypeRepository.findByProject_WorkspaceId(ws)
                .stream().collect(Collectors.toMap(TaskType::getId, TaskType::getName));

        List<Task> tasks = taskRepository.findByWorkspaceIdOrdered(ws);

        if (date != null && !date.isBlank()) {
            LocalDate ld = LocalDate.parse(date);
            Instant dayStart = ld.atStartOfDay(ZONE_BR).toInstant();
            Instant dayEnd = ld.plusDays(1).atStartOfDay(ZONE_BR).toInstant();
            tasks = tasks.stream().filter(t -> touchedBetween(t, dayStart, dayEnd)).toList();
        } else if (startDate != null && !startDate.isBlank() && endDate != null && !endDate.isBlank()) {
            Instant rangeStart = LocalDate.parse(startDate).atStartOfDay(ZONE_BR).toInstant();
            Instant rangeEnd = LocalDate.parse(endDate).plusDays(1).atStartOfDay(ZONE_BR).toInstant();
            tasks = tasks.stream().filter(t -> touchedBetween(t, rangeStart, rangeEnd)).toList();
        }

        if (status != null && !status.isBlank()) {
            tasks = tasks.stream().filter(t -> status.equalsIgnoreCase(t.getStatus())).toList();
        }
        if (project != null && !project.isBlank()) {
            String needle = project.toLowerCase();
            tasks = tasks.stream().filter(t -> {
                String pName = t.getProjectId() != null ? projectNames.get(t.getProjectId()) : null;
                return pName != null && pName.toLowerCase().contains(needle);
            }).toList();
        }

        List<Map<String, Object>> taskList = tasks.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", t.getTitle());
            m.put("description", t.getDescription() != null ? t.getDescription() : "");
            m.put("status", t.getStatus());
            m.put("priority", t.getPriority() != null ? t.getPriority() : "MEDIUM");
            m.put("project", t.getProjectId() != null ? projectNames.get(t.getProjectId()) : null);
            m.put("taskType", t.getTaskTypeId() != null ? taskTypeNames.get(t.getTaskTypeId()) : null);
            m.put("dueDate", t.getDueDate() != null ? t.getDueDate().toString() : null);
            m.put("createdAt", t.getCreatedAt().toString());
            m.put("updatedAt", t.getUpdatedAt().toString());
            return m;
        }).toList();

        Map<String, Long> countByStage = tasks.stream()
                .collect(Collectors.groupingBy(
                        t -> t.getStatus() != null ? t.getStatus() : "?",
                        LinkedHashMap::new, Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", taskList.size());
        result.put("countByStage", countByStage);
        result.put("tasks", taskList);
        return result;
    }

    @Tool(name = "get_projects", description = "Lista os projetos do usuário com nome e cor.")
    public List<Map<String, Object>> getProjects(
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        return projectRepository.findByWorkspaceIdOrderByNameAsc(ws).stream().map(p -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", p.getName());
            m.put("color", p.getColor());
            return m;
        }).toList();
    }

    @Tool(name = "get_task_types",
            description = "Lista os tipos de tarefa do usuário. Se projectName for informado, retorna apenas os tipos daquele projeto.")
    public List<Map<String, Object>> getTaskTypes(
            @ToolParam(required = false, description = "Nome exato do projeto para filtrar os tipos.") String projectName,
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        Map<Integer, String> projectNames = projectRepository.findByWorkspaceIdOrderByNameAsc(ws)
                .stream().collect(Collectors.toMap(Project::getId, Project::getName));

        return taskTypeRepository.findByProject_WorkspaceId(ws).stream()
                .filter(tt -> projectName == null || projectName.isBlank()
                        || projectName.equalsIgnoreCase(projectNames.getOrDefault(tt.getProjectId(), "")))
                .map(tt -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", tt.getName());
                    m.put("project", projectNames.get(tt.getProjectId()));
                    return m;
                }).toList();
    }

    // ── Write tools ───────────────────────────────────────────────────────────

    @Tool(name = "create_task",
            description = "Cria uma nova tarefa para o usuário. Chame apenas após coletar as informações necessárias. Etapa, projeto e tipo são referenciados por nome exato.")
    public Map<String, Object> createTask(
            @ToolParam(description = "Título da tarefa.") String title,
            @ToolParam(required = false, description = "Descrição opcional da tarefa.") String description,
            @ToolParam(required = false, description = "Nome exato da etapa inicial. Omita para usar a primeira etapa.") String status,
            @ToolParam(required = false, description = "Prioridade: HIGH, MEDIUM ou LOW. Padrão MEDIUM.") String priority,
            @ToolParam(required = false, description = "Nome exato do projeto a associar.") String projectName,
            @ToolParam(required = false, description = "Nome exato do tipo de tarefa a associar.") String taskTypeName,
            @ToolParam(required = false, description = "Prazo em ISO-8601 UTC, ex: 2026-06-20T12:00:00Z.") String dueDate,
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        if (McpPrincipalContext.isReadOnly()) return readOnlyError();
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        if (title == null || title.isBlank()) {
            return error("title é obrigatório");
        }

        Integer stageId = null;
        if (status != null && !status.isBlank()) {
            stageId = resolveStageId(ws, status);
            if (stageId == null) {
                return invalidStage(ws);
            }
        }

        Integer projectId = null;
        boolean projectNotFound = false;
        if (projectName != null && !projectName.isBlank()) {
            Optional<Project> p = projectRepository.findByWorkspaceIdOrderByNameAsc(ws).stream()
                    .filter(it -> it.getName().equalsIgnoreCase(projectName)).findFirst();
            if (p.isPresent()) projectId = p.get().getId();
            else projectNotFound = true;
        }

        Integer taskTypeId = null;
        boolean typeNotFound = false;
        if (taskTypeName != null && !taskTypeName.isBlank()) {
            Optional<TaskType> tt = taskTypeRepository.findByProject_WorkspaceId(ws).stream()
                    .filter(it -> it.getName().equalsIgnoreCase(taskTypeName)).findFirst();
            if (tt.isPresent()) taskTypeId = tt.get().getId();
            else typeNotFound = true;
        }

        Instant due;
        try {
            due = parseDueDate(dueDate);
        } catch (RuntimeException e) {
            return error("dueDate inválido; use ISO-8601 UTC, ex: 2026-06-20T12:00:00Z");
        }

        TaskRequest req = new TaskRequest(
                title, description, null, stageId, normalizePriority(priority),
                projectId, taskTypeId, null, due);
        Map<String, Object> created = taskService.create(req, uid, ws);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", created.get("id"));
        result.put("title", created.get("title"));
        result.put("status", created.get("status"));
        if (projectNotFound) result.put("projectNotFound", true);
        if (typeNotFound) result.put("typeNotFound", true);
        return result;
    }

    @Tool(name = "update_task",
            description = "Atualiza uma tarefa existente pelo id. Apenas os campos informados são alterados. Use list_stages/get_tasks para descobrir ids e nomes válidos.")
    public Map<String, Object> updateTask(
            @ToolParam(description = "ID da tarefa a atualizar.") Integer taskId,
            @ToolParam(required = false, description = "Novo título.") String title,
            @ToolParam(required = false, description = "Nova descrição.") String description,
            @ToolParam(required = false, description = "Nome exato da nova etapa (move a tarefa de coluna).") String status,
            @ToolParam(required = false, description = "Nova prioridade: HIGH, MEDIUM ou LOW.") String priority,
            @ToolParam(required = false, description = "Novo prazo em ISO-8601 UTC, ex: 2026-06-20T12:00:00Z.") String dueDate,
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        if (McpPrincipalContext.isReadOnly()) return readOnlyError();
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        if (taskId == null) {
            return error("taskId é obrigatório");
        }

        Integer stageId = null;
        if (status != null && !status.isBlank()) {
            stageId = resolveStageId(ws, status);
            if (stageId == null) {
                return invalidStage(ws);
            }
        }

        Instant due;
        try {
            due = parseDueDate(dueDate);
        } catch (RuntimeException e) {
            return error("dueDate inválido; use ISO-8601 UTC, ex: 2026-06-20T12:00:00Z");
        }

        TaskUpdateRequest req = new TaskUpdateRequest(
                emptyToNull(title), description, null, stageId, normalizePriority(priority),
                null, null, null, null, due);
        Map<String, Object> updated = taskService.update(taskId, req, uid, ws);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", updated.get("id"));
        result.put("title", updated.get("title"));
        result.put("status", updated.get("status"));
        return result;
    }

    @Tool(name = "move_task",
            description = "Move uma tarefa para outra etapa (coluna) pelo nome exato da etapa de destino.")
    public Map<String, Object> moveTask(
            @ToolParam(description = "ID da tarefa a mover.") Integer taskId,
            @ToolParam(description = "Nome exato da etapa de destino.") String targetStage,
            @ToolParam(required = false, description = "ID do workspace; omita para usar o pessoal.") Integer workspaceId) {
        if (McpPrincipalContext.isReadOnly()) return readOnlyError();
        int uid = userId();
        int ws = resolveWorkspace(workspaceId, uid);
        if (taskId == null) {
            return error("taskId é obrigatório");
        }
        Integer stageId = resolveStageId(ws, targetStage);
        if (stageId == null) {
            return invalidStage(ws);
        }

        TaskUpdateRequest req = new TaskUpdateRequest(
                null, null, null, stageId, null, null, null, null, null, null);
        Map<String, Object> updated = taskService.update(taskId, req, uid, ws);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("id", updated.get("id"));
        result.put("title", updated.get("title"));
        result.put("status", updated.get("status"));
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int userId() {
        return McpPrincipalContext.requireUserId();
    }

    /** Resolves the target workspace and enforces membership for the current user. */
    private int resolveWorkspace(Integer argWorkspaceId, int userId) {
        int ws = argWorkspaceId != null ? argWorkspaceId
                : McpPrincipalContext.workspaceId() != null ? McpPrincipalContext.workspaceId()
                : workspaceService.getPersonalWorkspaceId(userId);
        workspaceService.assertMember(ws, userId);
        return ws;
    }

    private Integer resolveStageId(int workspaceId, String stageName) {
        if (stageName == null || stageName.isBlank()) return null;
        return stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId).stream()
                .filter(s -> s.getName().equalsIgnoreCase(stageName))
                .findFirst().map(Stage::getId).orElse(null);
    }

    private boolean touchedBetween(Task t, Instant start, Instant end) {
        return (t.getCreatedAt().compareTo(start) >= 0 && t.getCreatedAt().isBefore(end))
                || (t.getUpdatedAt().compareTo(start) >= 0 && t.getUpdatedAt().isBefore(end));
    }

    private Instant parseDueDate(String dueDate) {
        return (dueDate == null || dueDate.isBlank()) ? null : Instant.parse(dueDate);
    }

    private String normalizePriority(String priority) {
        if (priority == null || priority.isBlank()) return null;
        String p = priority.trim().toUpperCase();
        return VALID_PRIORITIES.contains(p) ? p : null;
    }

    private String emptyToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private Map<String, Object> error(String message) {
        return Map.of("error", message);
    }

    private Map<String, Object> readOnlyError() {
        return Map.of("error", "Token somente-leitura: operação de escrita não permitida.");
    }

    private Map<String, Object> invalidStage(int workspaceId) {
        List<String> names = stageRepository.findByWorkspaceIdOrderByPositionAsc(workspaceId)
                .stream().map(Stage::getName).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", "Etapa não encontrada. Use um destes nomes exatos de etapa.");
        m.put("validStages", names);
        return m;
    }
}
