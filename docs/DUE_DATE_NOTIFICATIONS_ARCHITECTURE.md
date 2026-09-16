# DUE DATE & Email Notifications — Architecture Plan

> **Status:** Planning
> **Date:** 2026-03-11
> **Scope:** Backend (Spring Boot 3.5 / Java 21) + Frontend (React 19 / Vite)

---

## 1. Feature Overview

This document describes the full architecture for two tightly coupled features:

1. **Due Date on Tasks** — A nullable `dueDate` (date + time) field on every task.
2. **Email Notification Rules** — A sidebar "Notifications" section where users define email alert rules: _who_ to notify, _which tasks_ (workspace-wide or per project), and _when_ relative to the due date (e.g., 10 min before, 1 day before).

---

## 2. User Stories

| # | Story |
|---|-------|
| US-1 | As a user, I can set a due date + time on any task when creating or editing it. |
| US-2 | As a user, I can see the due date displayed on each task card, with color coding (overdue = red, due soon = orange). |
| US-3 | As a user, I can open a "Notifications" sidebar item to manage email notification rules. |
| US-4 | As a user, I can create a notification rule scoped to the entire workspace or to a specific project. |
| US-5 | As a user, I can add one or more recipient email addresses to a rule (not limited to registered users). |
| US-6 | As a user, I can choose one or more time offsets for the alert: 10 min before, 30 min before, 1 day before, or a custom offset. |
| US-7 | As a user, I can enable or disable a rule without deleting it. |
| US-8 | The system automatically sends notification emails to all recipients for all tasks with a due date that match an active rule. |

---

## 3. Existing Architecture Context

### Backend (Spring Boot 3.5 / Java 21)
- **Database:** PostgreSQL — all DDL via Flyway (currently at V10). Tables use PascalCase quoted names (`"Task"`, `"User"`, etc.).
- **ORM:** Spring Data JPA (Hibernate 6, `ddl-auto: validate`). Entities use Lombok `@Builder`, `@Getter`, `@Setter`.
- **Schema evolution:** Add new Flyway migrations (`V11`, `V12`, `V13`). Never alter existing migrations.
- **Response shape:** Services return `Map<String, Object>` (no MapStruct). All controllers are thin — business logic lives in `*Service` classes.
- **Authorization:** `WorkspaceService.assertMember(workspaceId, userId)` guards all workspace-scoped operations.
- **Real-time:** STOMP WebSocket via `WorkspaceEventPublisher` for shared workspaces.
- **Current gaps:** No email infrastructure, no scheduler, no job queue.

### Frontend (React 19 / Vite)
- **State:** All state in `DashboardPage.jsx` — no global state library.
- **Modals:** Bootstrap `<Modal>` + `custom-modal-content` CSS class. Every modal is controlled by `show*` boolean state in `DashboardPage`.
- **Sidebar:** Callback-based navigation — items call props (`onProjectsClick`, `onAiClick`, etc.).
- **API:** Axios with auth interceptor in `src/services/api.js`.
- **Date pickers:** `react-datepicker` already installed. `<Form.Control type="datetime-local">` pattern already used in `TaskFormModal`.
- **UI library:** React-Bootstrap + `react-bootstrap-icons`. CSS variables for theming.

---

## 4. Database Schema Changes

### V11 — Add `dueDate` to Task

```sql
-- V11__add_due_date_to_task.sql
ALTER TABLE public."Task"
    ADD COLUMN "dueDate" TIMESTAMPTZ;

CREATE INDEX idx_task_due_date ON public."Task"("dueDate")
    WHERE "dueDate" IS NOT NULL;
```

### V12 — Notification Rules & Schedules

```sql
-- V12__add_notification_system.sql

-- Rule: defines WHO gets notified, WHICH tasks, and WHEN (offsets)
CREATE TABLE public."NotificationRule" (
    id              SERIAL PRIMARY KEY,
    "workspaceId"   INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    "projectId"     INTEGER          REFERENCES public."Project"(id)   ON DELETE CASCADE,
    -- NULL projectId means the rule applies to ALL tasks in the workspace
    "createdById"   INTEGER NOT NULL REFERENCES public."User"(id)      ON DELETE CASCADE,
    "name"          VARCHAR(120) NOT NULL,
    "isActive"      BOOLEAN NOT NULL DEFAULT TRUE,
    "createdAt"     TIMESTAMPTZ NOT NULL DEFAULT now(),
    "updatedAt"     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_notification_rule_workspace ON public."NotificationRule"("workspaceId");
CREATE INDEX idx_notification_rule_project   ON public."NotificationRule"("projectId");

-- Recipient emails per rule (supports non-registered users)
CREATE TABLE public."NotificationRecipient" (
    id        SERIAL PRIMARY KEY,
    "ruleId"  INTEGER NOT NULL REFERENCES public."NotificationRule"(id) ON DELETE CASCADE,
    email     VARCHAR(255) NOT NULL,
    UNIQUE ("ruleId", email)
);

-- Offset values per rule, stored as minutes before due date
-- Examples: 10 = "10 minutes before", 1440 = "1 day before", 0 = "at due time"
CREATE TABLE public."NotificationOffset" (
    id        SERIAL PRIMARY KEY,
    "ruleId"  INTEGER NOT NULL REFERENCES public."NotificationRule"(id) ON DELETE CASCADE,
    minutes   INTEGER NOT NULL CHECK (minutes >= 0),
    UNIQUE ("ruleId", minutes)
);

-- Concrete scheduled sends — computed from tasks + rules
CREATE TABLE public."NotificationSchedule" (
    id               SERIAL PRIMARY KEY,
    "taskId"         INTEGER NOT NULL REFERENCES public."Task"(id)              ON DELETE CASCADE,
    "ruleId"         INTEGER NOT NULL REFERENCES public."NotificationRule"(id)  ON DELETE CASCADE,
    "recipientEmail" VARCHAR(255) NOT NULL,
    "offsetMinutes"  INTEGER NOT NULL,
    "scheduledAt"    TIMESTAMPTZ NOT NULL,
    "status"         VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    -- PENDING | SENT | FAILED | CANCELLED
    "sentAt"         TIMESTAMPTZ,
    "retryCount"     INTEGER NOT NULL DEFAULT 0,
    "nextRetryAt"    TIMESTAMPTZ,
    "errorMessage"   VARCHAR(500),
    "createdAt"      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("taskId", "ruleId", "recipientEmail", "offsetMinutes")
);

-- Partial index for the scheduler query — pending + retry-eligible schedules
CREATE INDEX idx_notification_schedule_pending
    ON public."NotificationSchedule"("scheduledAt")
    WHERE status = 'PENDING';

CREATE INDEX idx_notification_schedule_retry
    ON public."NotificationSchedule"("nextRetryAt")
    WHERE status = 'PENDING' AND "retryCount" > 0;
```

---

## 5. Backend Architecture

### 5.1 New Entities

#### `NotificationRule.java`
```
Fields: id, workspaceId, projectId (nullable), createdById, name, isActive, createdAt, updatedAt
Relations:
  @ManyToOne Workspace workspace
  @ManyToOne(optional) Project project
  @ManyToOne User createdBy
  @OneToMany(cascade=ALL, orphanRemoval=true) List<NotificationRecipient> recipients
  @OneToMany(cascade=ALL, orphanRemoval=true) List<NotificationOffset> offsets
```

#### `NotificationRecipient.java`
```
Fields: id, ruleId, email
Relations: @ManyToOne NotificationRule rule
```

#### `NotificationOffset.java`
```
Fields: id, ruleId, minutes
Relations: @ManyToOne NotificationRule rule
```

#### `NotificationSchedule.java`
```
Fields: id, taskId, ruleId, recipientEmail, offsetMinutes, scheduledAt, status, sentAt, errorMessage, createdAt
Relations:
  @ManyToOne Task task
  @ManyToOne NotificationRule rule
```

#### `Task.java` — add field
```java
@Column(name = "\"dueDate\"")
private Instant dueDate;
```

### 5.2 New Repositories

```
NotificationRuleRepository
  findByWorkspaceId(Integer workspaceId): List<NotificationRule>
  findByWorkspaceIdAndProjectId(Integer workspaceId, Integer projectId): List<NotificationRule>
  findActiveRulesForTask(Integer workspaceId, Integer projectId): List<NotificationRule>
    — @Query: rules where workspaceId matches AND (projectId IS NULL OR projectId = :projectId) AND isActive = true

NotificationScheduleRepository
  findByTaskId(Integer taskId): List<NotificationSchedule>
  findPendingDue(Instant now): List<NotificationSchedule>
    — @Query native: SELECT * FROM "NotificationSchedule" WHERE status = 'PENDING' AND "scheduledAt" <= :now LIMIT 100
  deleteByTaskIdAndStatus(Integer taskId, String status): void
  deleteByRuleIdAndStatus(Integer ruleId, String status): void
```

### 5.3 New Services

#### `NotificationRuleService`
```
createRule(NotificationRuleRequest, Integer userId, Integer workspaceId) → Map<String,Object>
  - workspaceService.assertCreator(workspaceId, userId)  ← only CREATOR role
  - validate: emails.size() <= 10 (BadRequestException if exceeded)
  - validate: offsets non-empty
  - resolve assignee email: if task has assignee, include their User.email in recipients
  - persist rule + recipients + offsets
  - call scheduleService.recomputeForRule(rule)

updateRule(Integer ruleId, NotificationRuleRequest, Integer userId, Integer workspaceId) → Map<String,Object>
  - workspaceService.assertCreator(workspaceId, userId)
  - validate: emails.size() <= 10
  - update name, isActive, recipients, offsets (replace collections via orphanRemoval)
  - call scheduleService.recomputeForRule(rule)

deleteRule(Integer ruleId, Integer userId, Integer workspaceId)
  - workspaceService.assertCreator(workspaceId, userId)
  - delete rule → cascade handles recipients, offsets, schedules

listRules(Integer workspaceId, Integer userId) → List<Map<String,Object>>
  - workspaceService.assertMember(workspaceId, userId)  ← any member can VIEW rules
  - return rules with recipients and offsets embedded
```

#### `NotificationScheduleService`
```
recomputeForTask(Task task)
  — Called when task dueDate changes (set, updated, cleared)
  — Steps:
    1. Delete all PENDING schedules for this task
    2. If task.dueDate == null → done
    3. Find active rules: workspaceId matches AND (projectId null OR projectId = task.projectId)
    4. For each rule → for each offset → for each recipient:
       scheduledAt = task.dueDate - offsetMinutes
       if scheduledAt > now() → persist NotificationSchedule(PENDING)

recomputeForRule(NotificationRule rule)
  — Called when a rule is created or updated
  — Steps:
    1. Delete all PENDING schedules for this rule
    2. If rule.isActive == false → done
    3. Find all tasks in scope (workspaceId = rule.workspaceId, projectId matches if rule.projectId not null, dueDate NOT NULL)
    4. For each task → for each offset → for each recipient: same logic as above

cancelSchedulesForTask(Integer taskId)
  — Called on task delete (before cascade, for clarity)
  — Update status = 'CANCELLED' for all PENDING rows (cascade will delete them anyway)
```

#### `EmailService`
```
sendDueDateNotification(NotificationSchedule schedule, Task task)
  — Builds rich HTML email:
     Subject: "[DailyTracker] Prazo da tarefa: <task.title>"
     Body (HTML):
       - DailyTracker header/logo text
       - Task title (bold)
       - Project name (if any)
       - Workspace name
       - Due date formatted (dd/MM/yyyy HH:mm)
       - Task description (if any)
       - CTA button → ${FRONTEND_URL}/dashboard?taskId={task.id}
       - Footer: "Você recebeu este email porque..."
  — Uses JavaMailSender with MimeMessage (HTML support)
  — Sender: ${NOTIFICATIONS_FROM_EMAIL} <${NOTIFICATIONS_FROM_NAME}>
  — On success: schedule.status = SENT, schedule.sentAt = now()
  — On failure (retryCount < 3):
       schedule.retryCount++
       schedule.nextRetryAt = now() + 60s
       schedule.errorMessage = ex.message
       (status stays PENDING)
  — On failure (retryCount >= 3):
       schedule.status = FAILED
       schedule.errorMessage = ex.message
```

#### `NotificationDispatchJob`
```java
@Component
public class NotificationDispatchJob {

    @Scheduled(fixedDelay = 60_000) // every 60 seconds
    @Transactional
    public void dispatch() {
        Instant now = Instant.now();
        // Fetch: PENDING schedules due now, AND retry-eligible (nextRetryAt <= now)
        List<NotificationSchedule> due = scheduleRepository.findPendingDue(now);
        for (NotificationSchedule s : due) {
            emailService.sendDueDateNotification(s, s.getTask());
        }
    }
}
// findPendingDue query:
// WHERE status = 'PENDING'
//   AND (("retryCount" = 0 AND "scheduledAt" <= :now)
//     OR ("retryCount" > 0 AND "nextRetryAt" <= :now))
// LIMIT 100
```

Note: `@EnableScheduling` added to a dedicated `SchedulingConfig.java`.

### 5.4 Modified Services

#### `TaskService` changes
- `toResponse(Task task)` → add `"dueDate"` key (ISO-8601 string or null)
- `create(...)` → after save, call `scheduleService.recomputeForTask(task)` if dueDate present
- `update(...)` → if `request.dueDate()` differs from `task.getDueDate()`, update and call `scheduleService.recomputeForTask(task)`
- `delete(...)` → cascade in DB handles schedules; no extra call needed

### 5.5 New DTOs

#### `NotificationRuleRequest` (Java Record)
```java
record NotificationRuleRequest(
    String name,
    Integer projectId,        // null = workspace-wide
    List<String> emails,      // non-empty, validated
    List<Integer> offsets     // minutes before due date, e.g. [10, 30, 1440]
)
```

#### `TaskRequest` / `TaskUpdateRequest` changes
- Add `Instant dueDate` field (nullable)

### 5.6 New REST Endpoints

**Base path:** `/api/notification-rules`
**Auth:** All endpoints require `Authorization: Bearer <token>` (protected by existing filter chain).

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/notification-rules?workspaceId={id}` | List all rules for a workspace (with recipients + offsets) |
| `POST` | `/api/notification-rules?workspaceId={id}` | Create a new rule |
| `PUT` | `/api/notification-rules/{ruleId}?workspaceId={id}` | Update rule (name, isActive, emails, offsets) |
| `DELETE` | `/api/notification-rules/{ruleId}?workspaceId={id}` | Delete rule + cancel its pending schedules |

**Response shape for a rule:**
```json
{
  "id": 1,
  "name": "Project Alpha alerts",
  "workspaceId": 3,
  "projectId": 7,
  "projectName": "Alpha",
  "isActive": true,
  "recipients": ["alice@example.com", "bob@example.com"],
  "offsets": [10, 1440],
  "createdAt": "2026-03-11T12:00:00Z"
}
```

### 5.7 New Configuration / Dependencies

#### `pom.xml` additions
```xml
<!-- Email -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>

<!-- Quartz (optional — only if @Scheduled proves insufficient at scale) -->
<!-- For v1, plain @Scheduled is sufficient -->
```

#### `application.yaml` additions
```yaml
spring:
  mail:
    host: ${SMTP_HOST:smtp.gmail.com}
    port: ${SMTP_PORT:587}
    username: ${SMTP_USERNAME}
    password: ${SMTP_PASSWORD}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true

app:
  notifications:
    from-email: ${NOTIFICATIONS_FROM_EMAIL:noreply@dailytracker.app}
    from-name: ${NOTIFICATIONS_FROM_NAME:DailyTracker}
    enabled: ${NOTIFICATIONS_ENABLED:true}
```

**New env vars required at deployment:**
- `SMTP_HOST` — SMTP server hostname
- `SMTP_PORT` — SMTP port (587 for STARTTLS, 465 for SSL)
- `SMTP_USERNAME` — SMTP auth username
- `SMTP_PASSWORD` — SMTP auth password / API key
- `NOTIFICATIONS_FROM_EMAIL` — sender address
- `NOTIFICATIONS_ENABLED` — feature flag to disable sending globally (e.g., in dev)

**Recommended SMTP providers:**
- **Resend** (resend.com) — modern, generous free tier, excellent deliverability
- **SendGrid** — via SMTP relay, widely used
- **Gmail SMTP** — for dev/small scale (`smtp.gmail.com:587`, app password)

---

## 6. Frontend Architecture

### 6.1 New Sidebar Item — "Notifications"

**File:** `src/components/Sidebar.jsx`

Add a new `Nav.Link` for "Notifications" below the "AI Assistant" item (or as a standalone section):

```jsx
// New prop: onNotificationsClick
// New icon import: BellFill from 'react-bootstrap-icons/dist/icons/bell-fill'

<Nav.Link
  onClick={isCollapsed ? () => { onToggleCollapse(); onNotificationsClick(); } : onNotificationsClick}
  style={linkStyle}
  onMouseEnter={...}
  onMouseLeave={...}
  role="button"
>
  <BellFill size={16} className="flex-shrink-0" />
  {!isCollapsed && t('sidebar.notifications')}
</Nav.Link>
```

### 6.2 New Modal — `NotificationsModal.jsx`

**File:** `src/components/NotificationsModal.jsx`

Follows the exact existing modal pattern:

```
Props: show, handleClose, workspaceId, projects (array for project dropdown)
State:
  rules (array from GET /api/notification-rules)
  showRuleForm (boolean)
  editingRule (object | null)
  loading, saving

Layout (Bootstrap Modal, size="lg"):
  Modal.Header: "Notificações por Email" / i18n key + close button
  Modal.Body:
    [If no rules]: Empty state illustration + "Criar primeira regra" button
    [Rules list]: Table or card list
      — Per rule: name, scope badge (workspace/project), recipients count, offsets badges, active toggle, edit/delete actions
    [Rule form panel / inline accordion]:
      — Rule name input
      — Scope: radio "Workspace inteiro" | "Projeto específico" + project dropdown
      — Recipients: email input + "Adicionar" button + chips list
      — Offsets: checkbox group
          ☐ 10 minutos antes
          ☐ 30 minutos antes
          ☐ 1 hora antes
          ☐ 1 dia antes
          ☐ Personalizado: [___] minutos
      — Save / Cancel buttons
```

**API calls inside modal:**
```js
GET  /api/notification-rules?workspaceId=X  → load rules on mount
POST /api/notification-rules?workspaceId=X  → create
PUT  /api/notification-rules/:id?workspaceId=X → update
DELETE /api/notification-rules/:id?workspaceId=X → delete
```

### 6.3 `DashboardPage.jsx` changes

```jsx
// New state
const [showNotificationsModal, setShowNotificationsModal] = useState(false);

// New handler
const handleNotificationsClick = () => setShowNotificationsModal(true);

// Wire to sidebar (both desktop and mobile Offcanvas usages):
<Sidebar
  ...
  onNotificationsClick={handleNotificationsClick}
/>

// Add modal at the bottom of JSX:
<NotificationsModal
  show={showNotificationsModal}
  handleClose={() => setShowNotificationsModal(false)}
  workspaceId={activeWorkspaceId}
  projects={projects}
/>
```

### 6.4 `TaskFormModal.jsx` changes

Add due date field to the create/edit task form. Use the existing `datetime-local` pattern already present in the file:

```jsx
<Form.Group className="mb-3">
  <Form.Label>{t('task.dueDate')}</Form.Label>
  <Form.Control
    type="datetime-local"
    value={dueDate}
    onChange={e => setDueDate(e.target.value)}
    style={{ colorScheme: theme === 'dark' ? 'dark' : 'light' }}
  />
  <Form.Text className="text-muted">
    {t('task.dueDateHint')}
  </Form.Text>
</Form.Group>
```

The value is sent to the API as an ISO-8601 string: `new Date(dueDate).toISOString()`.

### 6.5 `TaskCard.jsx` changes

Add a due date badge below the task title/description area:

```jsx
// Import CalendarEvent icon
// Logic:
const now = new Date();
const due = task.dueDate ? new Date(task.dueDate) : null;
const isOverdue = due && due < now && task.status !== 'DONE';
const isDueSoon = due && !isOverdue && (due - now) < 24 * 60 * 60 * 1000;

// Render:
{due && (
  <span style={{
    fontSize: '0.72rem',
    color: isOverdue ? 'var(--danger)' : isDueSoon ? '#f59e0b' : 'var(--text-muted)',
    display: 'flex',
    alignItems: 'center',
    gap: '0.25rem',
    marginTop: '0.35rem'
  }}>
    <CalendarEvent size={11} />
    {format(due, 'dd MMM, HH:mm', { locale: dateFnsLocale })}
    {isOverdue && ` (${t('task.overdue')})`}
  </span>
)}
```

### 6.6 i18n keys to add

**`pt-BR.json`** (and en-US, es equivalents):
```json
{
  "sidebar": {
    "notifications": "Notificações"
  },
  "task": {
    "dueDate": "Data de prazo",
    "dueDateHint": "Opcional. Define quando a tarefa deve ser concluída.",
    "overdue": "atrasada"
  },
  "notifications": {
    "title": "Notificações por Email",
    "noRules": "Nenhuma regra de notificação configurada",
    "createFirst": "Criar primeira regra",
    "createRule": "Nova regra",
    "ruleName": "Nome da regra",
    "scopeAll": "Workspace inteiro",
    "scopeProject": "Projeto específico",
    "recipients": "Destinatários",
    "addEmail": "Adicionar email",
    "offsets": "Alertas",
    "offset10min": "10 minutos antes",
    "offset30min": "30 minutos antes",
    "offset1h": "1 hora antes",
    "offset1d": "1 dia antes",
    "offsetCustom": "Personalizado",
    "offsetCustomUnit": "minutos antes",
    "activeToggle": "Ativa",
    "saveRule": "Salvar regra",
    "deleteRule": "Excluir regra",
    "deleteRuleConfirm": "Excluir esta regra cancelará todos os alertas pendentes. Confirmar?",
    "ruleSaved": "Regra salva com sucesso",
    "ruleDeleted": "Regra excluída"
  }
}
```

---

## 7. Data Flow Diagrams

### 7.1 Task Due Date Set → Schedules Created

```
User edits task (sets dueDate)
  └─▶ PUT /api/tasks/:id { dueDate: "2026-03-15T14:00:00Z" }
        └─▶ TaskService.update()
              ├─▶ task.setDueDate(...)
              ├─▶ taskRepository.saveAndFlush(task)
              ├─▶ NotificationScheduleService.recomputeForTask(task)
              │     ├─▶ DELETE PENDING schedules for this task
              │     ├─▶ NotificationRuleRepository.findActiveRulesForTask(workspaceId, projectId)
              │     └─▶ For each rule × offset × recipient:
              │           scheduledAt = dueDate - offsetMinutes
              │           if scheduledAt > now() → INSERT NotificationSchedule(PENDING)
              └─▶ WorkspaceEventPublisher.publishTaskEvent("TASK_UPDATED", ...)
```

### 7.2 Scheduler Fires → Email Sent

```
@Scheduled(fixedDelay=60s)
  └─▶ NotificationDispatchJob.dispatch()
        └─▶ SELECT * FROM "NotificationSchedule"
              WHERE status='PENDING' AND "scheduledAt" <= now()
              LIMIT 100
              └─▶ For each schedule:
                    EmailService.sendDueDateNotification(schedule, task)
                      ├─▶ JavaMailSender.send(...)
                      ├─▶ [success] schedule.status = SENT, sentAt = now()
                      └─▶ [failure] schedule.status = FAILED, errorMessage = ex.msg
```

### 7.3 Rule Created → Schedules Recomputed

```
User creates notification rule
  └─▶ POST /api/notification-rules?workspaceId=X { name, projectId?, emails, offsets }
        └─▶ NotificationRuleService.createRule()
              ├─▶ Persist NotificationRule + NotificationRecipients + NotificationOffsets
              └─▶ NotificationScheduleService.recomputeForRule(rule)
                    ├─▶ DELETE PENDING schedules for this rule (none yet on create)
                    ├─▶ Find tasks in scope with dueDate NOT NULL and dueDate > now()
                    └─▶ For each task × offset × recipient:
                          INSERT NotificationSchedule(PENDING)
```

---

## 8. Implementation Plan (Ordered by Dependency)

### Phase 1 — Backend: Due Date (Foundation)
1. `V11__add_due_date_to_task.sql` — Flyway migration
2. `Task.java` — add `dueDate` field
3. `TaskRequest.java` / `TaskUpdateRequest.java` — add `dueDate` field
4. `TaskService.java` — include `dueDate` in `toResponse()`, handle in `create()` and `update()`

### Phase 2 — Backend: Email Infrastructure
5. Add `spring-boot-starter-mail` to `pom.xml`
6. `application.yaml` — add `spring.mail.*` and `app.notifications.*` config
7. `EmailService.java` — `sendDueDateNotification()` using `JavaMailSender`
8. Email template (plain HTML string or Thymeleaf if added later)

### Phase 3 — Backend: Notification Domain
9. `V12__add_notification_system.sql` — Flyway migration
10. Entities: `NotificationRule`, `NotificationRecipient`, `NotificationOffset`, `NotificationSchedule`
11. Repositories: all 4 repositories with custom queries
12. `NotificationScheduleService` — `recomputeForTask()`, `recomputeForRule()`
13. `NotificationRuleService` — CRUD + schedule recomputation calls
14. `NotificationRuleController` — 4 endpoints
15. `NotificationDispatchJob` — `@Scheduled` dispatcher
16. `SchedulingConfig.java` — `@EnableScheduling`

### Phase 4 — Backend: Wire Due Date → Schedules
17. `TaskService.java` — inject `NotificationScheduleService`, call `recomputeForTask()` on dueDate change

### Phase 5 — Frontend: Due Date
18. `TaskFormModal.jsx` — add `dueDate` datetime-local field
19. `TaskCard.jsx` — add due date badge with color logic
20. i18n keys for `task.dueDate`, `task.overdue`

### Phase 6 — Frontend: Notifications UI
21. `Sidebar.jsx` — add "Notifications" `Nav.Link` + `BellFill` icon + `onNotificationsClick` prop
22. `NotificationsModal.jsx` — full notifications management UI
23. `DashboardPage.jsx` — wire `showNotificationsModal` state + modal + sidebar prop
24. i18n keys for `notifications.*` and `sidebar.notifications`

---

## 9. Edge Cases & Decisions

| Scenario | Decision |
|----------|----------|
| Task dueDate is updated | Delete all PENDING schedules for that task, recompute from scratch |
| Task is deleted | `ON DELETE CASCADE` on `NotificationSchedule."taskId"` handles cleanup automatically |
| Rule is deleted | `ON DELETE CASCADE` on `NotificationSchedule."ruleId"` handles cleanup automatically |
| Rule is disabled (`isActive=false`) | Cancel all PENDING schedules for this rule on update |
| Due date is in the past | Do not create schedules (skip if `scheduledAt <= now()`) |
| Scheduler runs late (e.g. server restart) | Sends all overdue PENDING schedules immediately on restart — acceptable for v1 |
| Duplicate schedules | UNIQUE constraint on `(taskId, ruleId, recipientEmail, offsetMinutes)` prevents duplicates |
| Recipients are non-users | `NotificationRecipient.email` is a plain string — no User FK required |
| Multiple rules match a task | All matching rules fire independently — each produces its own schedule rows |
| SMTP failure | Schedule marked `FAILED`; no automatic retry in v1 (can add retry in v2) |
| `NOTIFICATIONS_ENABLED=false` | `EmailService` checks flag and skips send; schedules still computed (safe) |
| Personal workspace | Notification rules work on personal workspaces. `WorkspaceEventPublisher` skips WS broadcast for personal workspaces, but scheduling still runs. |
| Task moved to different project | `recomputeForTask()` is called on any update that touches `projectId` — schedules are recomputed with the new project's rules |

---

## 10. File Change Summary

### Backend — New Files
```
src/main/java/com/dailytracker/api/
  entity/
    NotificationRule.java
    NotificationRecipient.java
    NotificationOffset.java
    NotificationSchedule.java
  repository/
    NotificationRuleRepository.java
    NotificationRecipientRepository.java
    NotificationOffsetRepository.java
    NotificationScheduleRepository.java
  service/
    NotificationRuleService.java
    NotificationScheduleService.java
    EmailService.java
  job/
    NotificationDispatchJob.java
  config/
    SchedulingConfig.java
  controller/
    NotificationRuleController.java
  dto/request/
    NotificationRuleRequest.java

src/main/resources/
  db/migration/
    V11__add_due_date_to_task.sql
    V12__add_notification_system.sql
```

### Backend — Modified Files
```
pom.xml                                  (add spring-boot-starter-mail)
src/main/resources/application.yaml     (add spring.mail.*, app.notifications.*)
src/main/java/.../entity/Task.java       (add dueDate field)
src/main/java/.../dto/request/TaskRequest.java         (add dueDate)
src/main/java/.../dto/request/TaskUpdateRequest.java   (add dueDate)
src/main/java/.../service/TaskService.java             (toResponse + recomputeForTask calls)
```

### Frontend — New Files
```
src/components/NotificationsModal.jsx
```

### Frontend — Modified Files
```
src/components/Sidebar.jsx            (add Notifications item + onNotificationsClick prop)
src/components/TaskFormModal.jsx      (add dueDate field)
src/components/TaskCard.jsx           (add due date badge)
src/pages/DashboardPage.jsx           (wire modal + sidebar prop)
src/i18n/locales/pt-BR.json           (new keys)
src/i18n/locales/en-US.json           (new keys)
src/i18n/locales/es.json              (new keys)
```

---

## 11. Decisions Log

| # | Question | Decision |
|---|----------|----------|
| 1 | Email template format | Rich HTML inline in `EmailService` (no Thymeleaf — not worth the dependency) |
| 2 | Task link in email | Yes — deep link to `${FRONTEND_URL}/dashboard` with the task highlighted (anchor `#task-{id}` or query param) |
| 3 | Rule ownership | Only the **workspace creator** (`role = CREATOR`) can create/edit/delete notification rules |
| 4 | Retry on failure | Up to **3 attempts** with 60s delay between retries. Track `retryCount` on `NotificationSchedule`. After 3 failures → `status = FAILED` permanently |
| 5 | Max recipients | **10 email addresses** per rule. Validated in `NotificationRuleService` (throws `BadRequestException` if exceeded) |
| 6 | Assignee auto-recipient | Assignee is **pre-checked** in the recipients form when editing. User can uncheck to exclude them. Assignee's email is resolved from their `User.email` at schedule computation time |
| 7 | Due date on Modern/Swimlane view | Show in **both Classic and Modern** views — `TaskCard` is shared by both views |
| 8 | SMTP provider | **Resend.com** (via SMTP relay or Resend Java SDK) |

---

## 12. Due Date Badge — TaskCard Layout Decision

### Current TaskCard Structure (3 rows):
```
Row 1: [title — single line, ellipsis overflow]
Row 2: [description — 1 line, expands on hover]
Row 3: [▲ priority] [createdAt date] [createdAt time]    [assignee pill] [taskType pill]
```

### Due Date Badge — Placement
Insert a **new compact Row 2.5** between description and the bottom meta row, **only rendered when `task.dueDate` is not null**:

```
Row 1:   [title]
Row 2:   [description]
Row 2.5: [📅 15 Mar, 14:00 · atrasada]     ← NEW (conditional)
Row 3:   [▲ priority] [createdAt]           [assignee] [taskType]
```

**Visual spec:**
- Font size: `0.73rem`
- `CalendarEvent` icon (react-bootstrap-icons), size 11
- Colors:
  - **Overdue** (dueDate < now AND status ≠ DONE): `var(--danger)` (#ef4444)
  - **Due soon** (dueDate within 24h AND not overdue): `#f59e0b` (amber — same as MEDIUM priority)
  - **Normal**: `var(--text-muted)`
- Display: `flex`, `alignItems: center`, gap `0.3rem`, `marginTop: 0.3rem`
- When status is DONE: no color coding (always `var(--text-muted)`) — task is completed, no urgency

### Why this position:
- **Not in Row 3** — that row is already dense with priority + timestamps + assignee + taskType
- **Not above title** — would break visual hierarchy (title must remain the primary anchor)
- **Row 2.5 is clean** — due date is contextual metadata, logically follows description, precedes operational meta
