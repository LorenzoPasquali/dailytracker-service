# Workspace Feature — Architecture & Implementation Plan

**Feature**: Multi-workspace support with real-time collaboration
**Date**: 2026-03-10
**Status**: Approved for implementation

---

## 1. Overview

Cada usuário terá por padrão um **workspace pessoal** (contendo todos os dados existentes). Além disso, poderá criar **workspaces compartilhados** para convidar outros usuários a colaborar em tempo real.

### Regras de negócio

| Regra | Detalhe |
|---|---|
| Workspace pessoal | Imutável, não pode ser deletado, criado automaticamente no registro |
| Workspace compartilhado | Qualquer membro pode criar/editar/deletar tarefas |
| Deleção de workspace | Apenas o criador pode deletar; triple-confirmation; cascata em tarefas/projetos |
| Remoção de membro | Apenas o criador pode remover outros; qualquer membro pode sair |
| Convite | Link com token UUID, expira em 24h; convidado precisa criar conta para aceitar |
| Real-time | WebSocket (STOMP) por workspace; apenas workspaces compartilhados usam WS |
| AI Assistant | Desabilitado em workspaces compartilhados (por enquanto) |
| Dados existentes | Permanecem no workspace pessoal, sem migração manual |

---

## 2. Modelo de Dados

### 2.1 Novas tabelas

```sql
-- Workspace
CREATE TABLE public."Workspace" (
    id           SERIAL PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    "creatorId"  INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    "isPersonal" BOOLEAN NOT NULL DEFAULT FALSE,
    "createdAt"  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Membros do workspace
CREATE TABLE public."WorkspaceMember" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    "userId"      INTEGER NOT NULL REFERENCES public."User"(id) ON DELETE CASCADE,
    role          VARCHAR(20) NOT NULL DEFAULT 'MEMBER',  -- 'CREATOR' | 'MEMBER'
    "joinedAt"    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("workspaceId", "userId")
);

-- Tokens de convite
CREATE TABLE public."WorkspaceInvite" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    token         VARCHAR(64) NOT NULL UNIQUE,
    "expiresAt"   TIMESTAMPTZ NOT NULL,
    "createdAt"   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 2.2 Tabelas existentes modificadas

```sql
-- Adicionar workspaceId em Task e Project
ALTER TABLE public."Task"    ADD COLUMN "workspaceId" INTEGER REFERENCES public."Workspace"(id) ON DELETE CASCADE;
ALTER TABLE public."Project" ADD COLUMN "workspaceId" INTEGER REFERENCES public."Workspace"(id) ON DELETE CASCADE;
```

### 2.3 Migração dos dados existentes

A migration V6 fará automaticamente:
1. Criar um workspace pessoal (`isPersonal = true`) para cada usuário existente
2. Criar entrada em `WorkspaceMember` (role = 'CREATOR') para cada usuário
3. Atribuir `workspaceId` em todos os `Task` e `Project` existentes com base no `userId`
4. Adicionar `NOT NULL` constraint após o backfill

### 2.4 Índices

```sql
CREATE INDEX idx_workspace_member_user      ON public."WorkspaceMember"("userId");
CREATE INDEX idx_workspace_member_workspace ON public."WorkspaceMember"("workspaceId");
CREATE INDEX idx_task_workspace             ON public."Task"("workspaceId");
CREATE INDEX idx_project_workspace          ON public."Project"("workspaceId");
```

---

## 3. Arquitetura Backend

### 3.1 Novos arquivos

#### Entidades
- `entity/Workspace.java` — campos: `id`, `name`, `creatorId`, `isPersonal`, `createdAt`
- `entity/WorkspaceMember.java` — campos: `id`, `workspaceId`, `userId`, `role`, `joinedAt`
- `entity/WorkspaceInvite.java` — campos: `id`, `workspaceId`, `token`, `expiresAt`, `createdAt`

#### Repositórios
- `repository/WorkspaceRepository.java`
  - `findByCreatorIdAndIsPersonalTrue(Integer creatorId)`
  - `findAllByMemberUserId(Integer userId)` — JPQL via `WorkspaceMember`
- `repository/WorkspaceMemberRepository.java`
  - `findByWorkspaceIdAndUserId(Integer workspaceId, Integer userId)`
  - `existsByWorkspaceIdAndUserId(Integer workspaceId, Integer userId)`
  - `findByWorkspaceId(Integer workspaceId)` — listar membros
  - `deleteByWorkspaceIdAndUserId(Integer workspaceId, Integer userId)`
- `repository/WorkspaceInviteRepository.java`
  - `findByToken(String token)`

#### Serviços
- `service/WorkspaceService.java`
  - `findAllForUser(Integer userId)` → lista workspaces do usuário
  - `create(String name, Integer userId)` → cria workspace + entrada de membro CREATOR
  - `update(Integer workspaceId, String name, Integer userId)` → apenas CREATOR
  - `delete(Integer workspaceId, Integer userId)` → apenas CREATOR; não pode deletar personal
  - `createInviteToken(Integer workspaceId, Integer userId)` → gera UUID, TTL 24h
  - `getInvitePreview(String token)` → público; retorna nome do workspace
  - `acceptInvite(String token, Integer userId)` → valida TTL, adiciona membro
  - `removeMember(Integer workspaceId, Integer targetUserId, Integer requestingUserId)`
  - `assertMember(Integer workspaceId, Integer userId)` — helper de autorização
  - `getPersonalWorkspaceId(Integer userId)` — helper para fallback de queries

- `service/WorkspaceEventPublisher.java`
  - Injeta `SimpMessagingTemplate`
  - `publishTaskEvent(Integer workspaceId, String eventType, Map<String, Object> payload)`
  - `publishProjectEvent(Integer workspaceId, String eventType, Map<String, Object> payload)`
  - Não publica para workspaces pessoais

#### Configuração
- `config/WebSocketConfig.java` — STOMP endpoint em `/ws`, broker `/topic`, prefix `/app`
- `config/WebSocketSecurityConfig.java` — `ChannelInterceptor` que valida JWT no SUBSCRIBE
- `exception/ForbiddenException.java` — HTTP 403 (nova exception)

#### Controller
- `controller/WorkspaceController.java`

### 3.2 Arquivos modificados

#### Entidades
- `entity/Task.java` — adicionar `workspaceId` (Integer) + `workspace` (ManyToOne)
- `entity/Project.java` — idem

#### Repositórios
- `repository/TaskRepository.java` — adicionar queries com `workspaceId`:
  - `findByWorkspaceIdOrdered(Integer workspaceId)` — substitui `findByUserIdOrdered`
  - `findByIdAndWorkspaceId(Integer id, Integer workspaceId)`
  - `findMinPositionByWorkspaceIdAndStatus(...)` / `findMaxPositionByWorkspaceIdAndStatus(...)`
- `repository/ProjectRepository.java`
  - `findByWorkspaceIdOrderByNameAsc(Integer workspaceId)`
  - `findByIdAndWorkspaceId(Integer id, Integer workspaceId)`

#### Serviços
- `service/TaskService.java` — recebe `workspaceId` em todos os métodos; publica eventos WS
- `service/ProjectService.java` — idem

#### Controllers
- `controller/TaskController.java` — adicionar `@RequestParam(required=false) Integer workspaceId`; quando ausente, resolve via `workspaceService.getPersonalWorkspaceId(userId)`
- `controller/ProjectController.java` — idem
- `controller/AiController.java` — guard: retorna 403 se workspaceId não for personal

#### Config
- `config/SecurityConfig.java` — adicionar `permitAll` para `/ws/**` e `/api/workspaces/invite/**`
- `pom.xml` — adicionar `spring-boot-starter-websocket`

### 3.3 Endpoints da API

```
GET    /api/workspaces                        → lista workspaces do usuário autenticado
POST   /api/workspaces                        → cria workspace { name }
PUT    /api/workspaces/{id}                   → renomeia { name } — apenas CREATOR
DELETE /api/workspaces/{id}                   → deleta workspace — apenas CREATOR

POST   /api/workspaces/{id}/invite            → gera token de convite → { token, url }
GET    /api/workspaces/invite/{token}         → preview público → { workspaceName, creatorEmail }
POST   /api/workspaces/invite/{token}/accept  → aceita convite (autenticado)

DELETE /api/workspaces/{id}/members/{userId}  → remove membro ou sair do workspace
GET    /api/workspaces/{id}/members           → lista membros
```

### 3.4 WebSocket

**Endpoint:** `ws://host/ws` (com fallback SockJS)
**Tópico por workspace:** `/topic/workspace/{workspaceId}`

**Tipos de evento:**

| Tipo | Trigger |
|---|---|
| `TASK_CREATED` | Nova tarefa criada no workspace |
| `TASK_UPDATED` | Tarefa editada (título, status, prioridade, etc.) |
| `TASK_DELETED` | Tarefa deletada |
| `TASK_REORDERED` | Batch reorder de posições |
| `PROJECT_CREATED` | Novo projeto criado |
| `PROJECT_UPDATED` | Projeto editado |
| `PROJECT_DELETED` | Projeto deletado |
| `MEMBER_JOINED` | Novo membro aceitou convite |
| `MEMBER_LEFT` | Membro saiu ou foi removido |

**Payload padrão:**
```json
{
  "type": "TASK_CREATED",
  "payload": {
    "id": 123,
    "title": "...",
    "userId": 5,
    ...
  }
}
```

**Autenticação WS:** JWT enviado nos headers do frame STOMP CONNECT. O `ChannelInterceptor` valida o JWT no CONNECT e verifica membership no SUBSCRIBE.

---

## 4. Arquitetura Frontend

### 4.1 Novos arquivos

#### Hooks
- `hooks/useWorkspace.js`
  - Fetches `GET /api/workspaces` no mount
  - Persiste `activeWorkspaceId` em `localStorage`
  - Valida se o ID armazenado ainda está na lista (pode ter sido removido do workspace)
  - Retorna: `{ workspaces, activeWorkspace, setActiveWorkspace, isPersonal, loading }`

- `hooks/useWorkspaceSocket.js`
  - Conecta ao WebSocket apenas quando workspace não for personal
  - Usa `@stomp/stompjs` + `sockjs-client`
  - Mantém referência STOMP em `useRef` para evitar reconexões
  - Desconecta ao trocar de workspace
  - Retorna callback `onEvent(event)` que o DashboardPage consome

#### Componentes
- `components/WorkspaceSwitcher.jsx`
  - Dropdown no header: exibe nome do workspace ativo + chevron
  - Lista: workspace pessoal primeiro, depois compartilhados separados por divider
  - Botão "Novo workspace" no rodapé → abre WorkspaceModal
  - Ícone de configurações no workspace ativo → abre WorkspaceModal em modo gerenciar

- `components/WorkspaceModal.jsx`
  - **Modo criar:** input de nome + submit
  - **Modo gerenciar:**
    - Renomear workspace
    - Gerar link de convite → exibe URL copiável
    - Lista de membros com emails; botão X para o criador remover
    - Botão "Sair do workspace" para membros não-criadores
    - Botão "Deletar workspace" — triple confirmation:
      1. `ConfirmationModal` genérico ("Tem certeza?")
      2. Input inline: "Digite o nome do workspace para confirmar" — validação client-side
      3. `ConfirmationModal` final com aviso explícito de perda de dados

#### Páginas
- `pages/InviteAcceptPage.jsx`
  - Rota: `/invite/:token`
  - **Não autenticado:** chama `GET /api/workspaces/invite/{token}` → exibe nome do workspace + criador → botões "Criar conta" / "Fazer login"
  - **Autenticado:** chama `POST /api/workspaces/invite/{token}/accept` → toast + redirect para `/dashboard`

### 4.2 Arquivos modificados

#### App.jsx
- Adicionar rota lazy: `/invite/:token` → `InviteAcceptPage`

#### DashboardPage.jsx
- Usar `useWorkspace()` hook
- Usar `useWorkspaceSocket()` hook
- Adicionar `?workspaceId={activeWorkspace.id}` em todas as chamadas de API (`fetchData`, mutations)
- Ao trocar de workspace: resetar filtros (`selectedProjectIds`, `dateRange`) + refetch
- Handler de eventos WS:
  ```js
  // Ignorar eventos do próprio usuário
  if (event.payload?.userId === currentUser?.id) return;
  ```
  - `TASK_CREATED` / `TASK_UPDATED` → atualizar `taskColumns` local
  - `TASK_DELETED` → remover task + toast informativo
  - `TASK_REORDERED` / `PROJECT_*` → `fetchData()`
- Passar `activeWorkspace` como prop para `TaskFormModal`, `ProjectsModal`, `TaskTypesModal`
- Renderizar `WorkspaceSwitcher` no `AppHeader`

#### AppHeader.jsx
- Adicionar `WorkspaceSwitcher` à esquerda do título
- Adicionar props: `workspaces`, `activeWorkspace`, `onWorkspaceChange`, `onWorkspaceManage`

#### Sidebar.jsx
- Link do AI Assistant: desabilitado/oculto quando `!isPersonal`
- Tooltip: "AI não disponível em workspaces compartilhados"

#### RegisterPage.jsx / LoginPage.jsx
- Após login/registro bem-sucedido, verificar `sessionStorage.getItem('pendingInviteToken')`
- Se existir: chamar `POST /api/workspaces/invite/{token}/accept`, limpar sessionStorage, redirecionar para `/dashboard`

#### InviteAcceptPage (fluxo com não-autenticado)
- Ao clicar "Criar conta": `sessionStorage.setItem('pendingInviteToken', token)` → `navigate('/register')`
- Ao clicar "Fazer login": idem → `navigate('/login')`

#### package.json (frontend)
- Adicionar: `@stomp/stompjs`, `sockjs-client`

---

## 5. Fluxos principais

### 5.1 Acesso normal (workspace pessoal)

```
DashboardPage monta
→ useWorkspace() → GET /api/workspaces → [{ id:1, name:"Pessoal", isPersonal:true }]
→ activeWorkspace = workspace pessoal
→ fetchData() → GET /api/tasks?workspaceId=1 (ou sem param → fallback para pessoal)
→ comportamento idêntico ao atual
→ useWorkspaceSocket → NÃO conecta (isPersonal = true)
```

### 5.2 Criação e uso de workspace compartilhado

```
Usuário abre WorkspaceSwitcher → "Novo workspace"
→ WorkspaceModal (modo criar) → POST /api/workspaces { name: "Time Alpha" }
→ Workspace criado, usuário vira CREATOR
→ activeWorkspace muda para "Time Alpha"
→ DashboardPage reseta filtros + refetch (tasks/projects do novo workspace = vazios)
→ useWorkspaceSocket conecta a /topic/workspace/{id}
```

### 5.3 Convite e aceite

```
Criador abre WorkspaceModal → "Gerar link de convite"
→ POST /api/workspaces/{id}/invite
→ WorkspaceService.createInviteToken → UUID + expiresAt = now + 24h
→ Retorna { url: "https://app.dailytracker.com/invite/{token}" }
→ Modal exibe URL copiável

Convidado abre URL /invite/{token}
→ InviteAcceptPage
→ GET /api/workspaces/invite/{token} (público)
→ Exibe: "Você foi convidado para 'Time Alpha'"
→ Clica "Criar conta" → sessionStorage.setItem('pendingInviteToken', token) → /register

RegisterPage
→ Usuário preenche e submete
→ Registro + auto-login
→ Verifica pendingInviteToken
→ POST /api/workspaces/invite/{token}/accept
→ WorkspaceMember criado → MEMBER_JOINED event WS publicado
→ sessionStorage limpo → /dashboard com workspace "Time Alpha" ativo
```

### 5.4 Colaboração em tempo real

```
Usuário A edita tarefa no workspace 42
→ PUT /api/tasks/99?workspaceId=42
→ TaskService.update → salva → WorkspaceEventPublisher.publishTaskEvent(42, "TASK_UPDATED", data)
→ SimpMessagingTemplate.convertAndSend("/topic/workspace/42", event)

Usuário B (inscrito em /topic/workspace/42)
→ useWorkspaceSocket onEvent dispara
→ event.payload.userId != currentUser.id → processar
→ handleTaskUpdated(payload) → atualiza taskColumns local
```

### 5.5 Deleção de workspace (triple confirmation)

```
Passo 1: Botão "Deletar workspace" → ConfirmationModal genérico
Passo 2: Input inline "Digite 'Time Alpha' para confirmar" → validação client-side
Passo 3: ConfirmationModal final "Todas as tarefas e projetos serão deletados"
→ DELETE /api/workspaces/42
→ WorkspaceService.delete:
   - verifica role = CREATOR
   - verifica isPersonal = false
   - workspaceRepository.delete → CASCADE deleta Members, Invites, Tasks, Projects
→ Frontend: useWorkspace refetch → activeWorkspace volta para pessoal
→ toast.success("Workspace deletado")
```

---

## 6. Sequência de implementação

### Fase 1 — Database + Entidades (sem mudança de comportamento)
- [ ] Criar `V6__add_workspace_support.sql` com DDL + backfill
- [ ] Criar entidades `Workspace`, `WorkspaceMember`, `WorkspaceInvite`
- [ ] Modificar `Task` e `Project` para adicionar `workspaceId`
- [ ] Criar repositórios `WorkspaceRepository`, `WorkspaceMemberRepository`, `WorkspaceInviteRepository`
- [ ] Adicionar queries workspace-scoped em `TaskRepository` e `ProjectRepository`
- **Validação:** migration roda sem erros; tasks/projects existentes têm `workspaceId` preenchido

### Fase 2 — WorkspaceService + Controller
- [ ] Implementar `WorkspaceService` completo
- [ ] Implementar `ForbiddenException`
- [ ] Implementar `WorkspaceController`
- [ ] Atualizar `SecurityConfig` (permit `/ws/**`, `/api/workspaces/invite/**`)
- **Validação:** `GET /api/workspaces` retorna `[{ isPersonal: true }]` para usuário existente

### Fase 3 — Migrar Task/Project APIs (backward-compatible)
- [ ] Modificar `TaskService` para aceitar `workspaceId`, publicar eventos WS
- [ ] Modificar `ProjectService` idem
- [ ] Modificar `TaskController` e `ProjectController` com fallback para workspace pessoal
- [ ] Guard em `AiController`
- **Validação:** `GET /api/tasks` (sem param) retorna mesmas tarefas de antes

### Fase 4 — WebSocket
- [ ] Adicionar `spring-boot-starter-websocket` ao `pom.xml`
- [ ] Implementar `WebSocketConfig`
- [ ] Implementar `WebSocketSecurityConfig` com `ChannelInterceptor`
- [ ] Implementar `WorkspaceEventPublisher`
- [ ] Injetar publisher em `TaskService` e `ProjectService`
- [ ] Adicionar `@stomp/stompjs`, `sockjs-client` ao frontend
- **Validação:** duas sessões abertas; mutação em uma aparece no console da outra

### Fase 5 — Frontend Core (WorkspaceSwitcher)
- [ ] Implementar `useWorkspace.js`
- [ ] Implementar `WorkspaceSwitcher.jsx`
- [ ] Modificar `AppHeader.jsx` para renderizar `WorkspaceSwitcher`
- [ ] Modificar `DashboardPage.jsx` para usar `useWorkspace`, passar `workspaceId` em todas as chamadas
- **Validação:** workspace pessoal selecionado; dashboard funciona identicamente ao atual

### Fase 6 — Frontend Workspace Compartilhado
- [ ] Implementar `WorkspaceModal.jsx` (criar + gerenciar + triple delete confirm)
- [ ] Implementar `useWorkspaceSocket.js`
- [ ] Integrar socket hook no `DashboardPage.jsx`
- [ ] Desabilitar AI no Sidebar para workspaces compartilhados
- **Validação:** criar workspace, abrir em segunda sessão, criar tarefa, ver aparecer na primeira

### Fase 7 — Fluxo de Convite
- [ ] Implementar `InviteAcceptPage.jsx`
- [ ] Adicionar rota `/invite/:token` no `App.jsx`
- [ ] Modificar `RegisterPage.jsx` e `LoginPage.jsx` para `pendingInviteToken`
- **Validação:** fluxo completo de convite end-to-end com criação de conta

---

## 7. Considerações técnicas importantes

### Segurança
- Toda mutation verifica membership via `WorkspaceMemberRepository.existsByWorkspaceIdAndUserId`
- O campo `userId` em Task/Project passa a significar "criador da tarefa", não "dono" — autorização usa workspaceId
- Workspace pessoal nunca pode ser deletado (guard no service)
- Tokens de convite são UUIDs aleatórios; expiram em 24h; são invalidados automaticamente por CASCADE ao deletar workspace
- WS: o `ChannelInterceptor` rejeita SUBSCRIBE sem JWT válido ou sem membership

### Real-time
- O broker STOMP é in-memory (`enableSimpleBroker`). Para deploy multi-instância futuramente, migrar para broker externo (Redis/RabbitMQ)
- Frontend ignora eventos WS que vieram do próprio usuário (evita double-apply de estado)
- Eventos de reorder e mudanças de projeto disparam `fetchData()` completo (mais seguro que merge manual)

### Compatibilidade
- Todos os endpoints existentes mantêm comportamento sem `?workspaceId` → fallback transparente para workspace pessoal
- Nenhuma breaking change para clientes existentes

### Performance
- `existsByWorkspaceIdAndUserId` está no hot path de todo request em workspace compartilhado — índice composto garante index-only scan
- Subscription WS só ocorre em workspaces compartilhados — sem overhead para o caso de uso pessoal (maioria dos usuários)

---

## 8. Resumo de arquivos

### Backend — Criar (12 arquivos)
| Arquivo | Tipo |
|---|---|
| `db/migration/V6__add_workspace_support.sql` | Migration |
| `entity/Workspace.java` | Entidade |
| `entity/WorkspaceMember.java` | Entidade |
| `entity/WorkspaceInvite.java` | Entidade |
| `repository/WorkspaceRepository.java` | Repositório |
| `repository/WorkspaceMemberRepository.java` | Repositório |
| `repository/WorkspaceInviteRepository.java` | Repositório |
| `service/WorkspaceService.java` | Serviço |
| `service/WorkspaceEventPublisher.java` | Serviço WS |
| `controller/WorkspaceController.java` | Controller |
| `config/WebSocketConfig.java` | Config |
| `config/WebSocketSecurityConfig.java` | Config |
| `exception/ForbiddenException.java` | Exception |

### Backend — Modificar (8 arquivos)
| Arquivo | Mudança |
|---|---|
| `pom.xml` | Adicionar `spring-boot-starter-websocket` |
| `entity/Task.java` | Campo `workspaceId` + relação |
| `entity/Project.java` | Campo `workspaceId` + relação |
| `repository/TaskRepository.java` | Queries workspace-scoped |
| `repository/ProjectRepository.java` | Queries workspace-scoped |
| `service/TaskService.java` | Param `workspaceId`, eventos WS |
| `service/ProjectService.java` | Param `workspaceId`, eventos WS |
| `controller/TaskController.java` | Param `workspaceId` com fallback |
| `controller/ProjectController.java` | Param `workspaceId` com fallback |
| `controller/AiController.java` | Guard workspace compartilhado |
| `config/SecurityConfig.java` | Novas regras de permit |
| `exception/GlobalExceptionHandler.java` | Handler para `ForbiddenException` |

### Frontend — Criar (5 arquivos)
| Arquivo | Tipo |
|---|---|
| `hooks/useWorkspace.js` | Hook |
| `hooks/useWorkspaceSocket.js` | Hook |
| `components/WorkspaceSwitcher.jsx` | Componente |
| `components/WorkspaceModal.jsx` | Componente |
| `pages/InviteAcceptPage.jsx` | Página |

### Frontend — Modificar (6 arquivos)
| Arquivo | Mudança |
|---|---|
| `App.jsx` | Rota `/invite/:token` |
| `pages/DashboardPage.jsx` | useWorkspace, WS, workspaceId em APIs |
| `components/AppHeader.jsx` | WorkspaceSwitcher |
| `components/Sidebar.jsx` | Guard AI |
| `pages/RegisterPage.jsx` | pendingInviteToken |
| `pages/LoginPage.jsx` | pendingInviteToken |
| `package.json` | Dependências WS |
