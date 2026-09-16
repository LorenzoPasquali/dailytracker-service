# Colunas de Status Personalizáveis por Workspace

**Feature**: Gerenciamento dinâmico de colunas Kanban por workspace
**Data**: 2026-03-11
**Status**: Em análise / Pronto para implementação

---

## 1. Visão Geral

Atualmente o sistema possui **3 colunas fixas** (`PLANNED`, `DOING`, `DONE`) definidas como convenção de strings entre frontend e backend — não há nenhuma tabela ou entidade representando essas colunas. O objetivo desta feature é permitir que o criador do workspace configure **colunas personalizadas**, adicionando, renomeando, reordenando ou removendo colunas de status além das padrões.

### Motivação

| Problema atual | Solução proposta |
|---|---|
| 3 colunas são hardcoded em 6+ arquivos no frontend e 3+ no backend | Colunas carregadas dinamicamente da API por workspace |
| Usuário não pode adaptar o fluxo (ex: adicionar "Em Revisão") | CRUD completo de colunas na aba de Cadastros |
| Workspaces pessoais e compartilhados compartilham o mesmo conjunto de colunas fixo | Cada workspace tem sua própria configuração de colunas |

---

## 2. Viabilidade Técnica

### 2.1 Por que é altamente viável

**No backend**, o campo `status` na entidade `Task` já é um `VARCHAR` puro (sem enum Java, sem tipo enum PostgreSQL, sem `CHECK` constraint). Qualquer string passa pela validação atual (`@NotBlank`). Isso significa que **nenhuma alteração na tabela `Task` é necessária** para suportar novos valores de status.

```java
// Task.java — status atual (sem enum)
private String status;  // já aceita qualquer string
```

**No banco de dados**, não existe tabela, seed ou migration que defina as 3 colunas. Elas são pura convenção, o que significa que podemos introduzir uma tabela `WorkspaceColumn` sem conflito com dados existentes.

**No frontend**, as 3 colunas são definidas em poucos pontos centrais, todos candidatos a substituição direta:

| Arquivo | Ocorrência | Tipo de mudança |
|---|---|---|
| `DashboardPage.jsx:35` | `useState({ PLANNED: [], DOING: [], DONE: [] })` | Estado dinâmico baseado em API |
| `DashboardPage.jsx:486-490` | 3 `<KanbanColumn>` hardcoded | Loop sobre colunas da API |
| `KanbanColumn.jsx:7-11` | `statusColors` fixo | Cor vinda do objeto coluna |
| `KanbanSwimlane.jsx:9-15` | `STATUS_COLORS` + `STATUSES` fixos | Array/map dinâmico |
| `TaskFormModal.jsx:25` | default `'PLANNED'` | Primeira coluna da lista |

### 2.2 Pontos de atenção

| Ponto | Descrição | Impacto |
|---|---|---|
| `TaskService.resolvePositionForNewTask` | Lógica especial para `"PLANNED"` + prioridade `"LOW"` | Generalizar para coluna na posição 0 |
| `GeminiService.java` (linhas 230-231, 282) | Status enumerados no schema de tools do Gemini | Buscar colunas do workspace no contexto da IA |
| `messages.properties` (ai.status.*) | Chaves i18n fixas para os 3 status | Usar `label` da coluna diretamente |
| Dados existentes | Tarefas já têm `status = 'PLANNED'/'DOING'/'DONE'` | Migração cria as 3 colunas padrão para todos os workspaces existentes |
| Exclusão de coluna com tarefas | Coluna não pode ser excluída se tiver tarefas ativas | Validação obrigatória no backend |

---

## 3. Modelo de Dados

### 3.1 Nova tabela: `WorkspaceColumn`

```sql
CREATE TABLE public."WorkspaceColumn" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    key           VARCHAR(50) NOT NULL,       -- valor salvo em Task.status (ex: 'PLANNED')
    label         VARCHAR(100) NOT NULL,      -- nome exibido (ex: 'Planejado')
    color         VARCHAR(20) NOT NULL DEFAULT '#6b7280',  -- hex ou CSS var
    position      INTEGER NOT NULL DEFAULT 0, -- ordem de exibição
    "isDefault"   BOOLEAN NOT NULL DEFAULT FALSE,  -- colunas padrão não podem ser deletadas
    "createdAt"   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("workspaceId", key)
);
```

### 3.2 Regras de negócio

| Regra | Detalhe |
|---|---|
| Colunas padrão | Criadas automaticamente (`isDefault=true`) ao criar qualquer workspace: PLANNED, DOING, DONE |
| Gerenciamento | Apenas o **criador** do workspace pode adicionar, renomear, reordenar ou remover colunas |
| Restrição de exclusão | Coluna com tarefas associadas **não pode ser excluída** (erro 409) |
| Restrição de colunas padrão | Colunas com `isDefault=true` **não podem ser excluídas**, mas podem ser renomeadas e ter a cor alterada |
| Mínimo de colunas | O workspace deve ter **pelo menos 1 coluna** em todo momento |
| Máximo de colunas | Limite sugerido: **10 colunas** por workspace (evitar UX degradada) |
| Unicidade de key | A `key` (valor de status) deve ser única por workspace, gerada no backend (slug do label) |
| Sincronização WS | Eventos `COLUMN_CREATED`, `COLUMN_UPDATED`, `COLUMN_DELETED`, `COLUMN_REORDERED` transmitidos via WebSocket para workspaces compartilhados |

### 3.3 Migration (V11)

```sql
-- V11__add_workspace_columns.sql

-- Criar tabela de colunas
CREATE TABLE public."WorkspaceColumn" (
    id            SERIAL PRIMARY KEY,
    "workspaceId" INTEGER NOT NULL REFERENCES public."Workspace"(id) ON DELETE CASCADE,
    key           VARCHAR(50) NOT NULL,
    label         VARCHAR(100) NOT NULL,
    color         VARCHAR(20) NOT NULL DEFAULT '#6b7280',
    position      INTEGER NOT NULL DEFAULT 0,
    "isDefault"   BOOLEAN NOT NULL DEFAULT FALSE,
    "createdAt"   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE ("workspaceId", key)
);

-- Backfill: criar colunas padrão para todos os workspaces existentes
INSERT INTO public."WorkspaceColumn" ("workspaceId", key, label, color, position, "isDefault")
SELECT w.id, 'PLANNED', 'Planejado',    'var(--text-muted)', 0, TRUE  FROM public."Workspace" w
UNION ALL
SELECT w.id, 'DOING',   'Em Andamento', '#f59e0b',           1, TRUE  FROM public."Workspace" w
UNION ALL
SELECT w.id, 'DONE',    'Concluído',    'var(--accent)',      2, TRUE  FROM public."Workspace" w;
```

---

## 4. Arquitetura Backend

### 4.1 Nova entidade: `WorkspaceColumn`

```java
@Entity
@Table(name = "\"WorkspaceColumn\"")
public class WorkspaceColumn {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "\"workspaceId\"", nullable = false)
    private Workspace workspace;

    private String key;       // valor armazenado em Task.status
    private String label;     // nome exibido no frontend
    private String color;
    private Integer position;
    private Boolean isDefault;

    @Column(name = "\"createdAt\"")
    private OffsetDateTime createdAt;
}
```

### 4.2 Novos endpoints REST

Todos os endpoints são aninhados em `/api/workspaces/{workspaceId}/columns` e exigem que o usuário seja **membro** do workspace. Operações de escrita (`POST`, `PUT`, `DELETE`, `PATCH`) exigem role `CREATOR`.

| Método | Path | Descrição | Auth |
|--------|------|-----------|------|
| `GET` | `/api/workspaces/{id}/columns` | Listar colunas ordenadas por position | Membro |
| `POST` | `/api/workspaces/{id}/columns` | Criar nova coluna | Criador |
| `PUT` | `/api/workspaces/{id}/columns/{columnId}` | Renomear/recolorir coluna | Criador |
| `DELETE` | `/api/workspaces/{id}/columns/{columnId}` | Excluir coluna (sem tarefas) | Criador |
| `PATCH` | `/api/workspaces/{id}/columns/reorder` | Reordenar colunas (array de IDs) | Criador |

### 4.3 DTOs

```java
// Request — criação
record ColumnCreateRequest(@NotBlank String label, String color) {}

// Request — atualização
record ColumnUpdateRequest(String label, String color) {}

// Request — reordenar
record ColumnReorderRequest(List<Integer> orderedIds) {}

// Response
record ColumnResponse(
    Integer id, String key, String label,
    String color, Integer position, Boolean isDefault
) {}
```

### 4.4 Adaptações no código existente

**`WorkspaceService`**: Método `createPersonalWorkspace` e `create` devem chamar `columnService.seedDefaultColumns(workspace)` após persistir o workspace.

**`TaskService.resolvePositionForNewTask`**: Remover o hardcode `"PLANNED"` — substituir pela verificação se o status da tarefa corresponde à **primeira coluna** do workspace (menor `position`).

**`GeminiService`**: Buscar as colunas do workspace ativo e gerar dinamicamente o schema `enum_` do campo `status` nos tools `create_task` e `get_tasks`.

**`messages.properties`**: As chaves `ai.status.PLANNED`, `ai.status.DOING`, `ai.status.DONE` podem ser substituídas pelo `label` da coluna vindo do banco, tornando o AI multilíngue automaticamente.

---

## 5. Arquitetura Frontend

### 5.1 Carregamento dinâmico de colunas

O `useWorkspace` hook (ou um novo `useWorkspaceColumns` hook) deve:
1. Carregar `GET /api/workspaces/{id}/columns` sempre que o workspace ativo mudar
2. Expor `columns: ColumnResponse[]` para o `DashboardPage`

```js
// Substituição em DashboardPage.jsx
const initialColumns = columns.reduce((acc, col) => ({ ...acc, [col.key]: [] }), {});
const [taskColumns, setTaskColumns] = useState(initialColumns);
```

### 5.2 Renderização dinâmica do Kanban

**Vista Classic** (`DashboardPage.jsx`):
```jsx
{columns.map(col => (
  <KanbanColumn
    key={col.key}
    title={col.label}
    status={col.key}
    color={col.color}
    tasks={filteredTasks[col.key] || []}
    ...
  />
))}
```

**Vista Modern** (`KanbanSwimlane.jsx`):
- Receber `columns` como prop, substituir `STATUSES` e `STATUS_COLORS` hardcoded

**`KanbanColumn.jsx`**:
- Receber `color` como prop, remover `statusColors` interno

**`TaskFormModal.jsx`**:
- Receber `columns` como prop, popular dropdown de status dinamicamente
- Default: `columns[0].key` (primeira coluna)

### 5.3 UI de gerenciamento — Sidebar (aba Cadastros)

Adicionar um novo item **"Colunas"** no `Accordion` de Cadastros no `Sidebar.jsx`, ao lado de **Projetos** e **Tipos de Tarefa**:

```jsx
<Nav.Link onClick={onColumnsClick} style={{ ...linkStyle, paddingLeft: '2.25rem' }}>
  <KanbanFill size={14} /> {t('sidebar.columns')}
</Nav.Link>
```

Visibilidade: o item só aparece se o usuário for **criador** do workspace ativo (membros veem as colunas mas não podem editá-las).

### 5.4 Novo modal: `ColumnsModal`

Seguindo o padrão visual de `ProjectsModal` e `TaskTypesModal`:

- **Lista** de colunas com drag-and-drop para reordenar (usando a mesma lib `@dnd-kit`)
- **Badge colorido** ao lado do nome (cor customizável via color picker simples)
- **Botão editar** (lápis) — abre inline para renomear + alterar cor
- **Botão excluir** (lixeira) — desabilitado se `isDefault=true` ou se a coluna tiver tarefas
- **Botão "+ Nova Coluna"** no rodapé
- Feedback visual: ao tentar excluir coluna com tarefas, mostrar tooltip explicativo

---

## 6. Eventos WebSocket

Para workspaces compartilhados, as colunas devem ser sincronizadas em tempo real. Novos tipos de evento a adicionar no `WorkspaceEventPublisher`:

```java
public enum WorkspaceEventType {
    // ... eventos existentes ...
    COLUMN_CREATED,
    COLUMN_UPDATED,
    COLUMN_DELETED,
    COLUMN_REORDERED
}
```

O frontend (`useWorkspaceSocket`) deve escutar esses eventos e atualizar o estado de colunas + reconstruir `taskColumns` sem reload completo.

---

## 7. Plano de Implementação

### Fase 1 — Backend (Fundação)
- [ ] Criar entidade `WorkspaceColumn` + repository
- [ ] Criar migration `V11__add_workspace_columns.sql` (tabela + backfill)
- [ ] Criar `WorkspaceColumnService` (CRUD + seedDefaultColumns + validações)
- [ ] Criar `WorkspaceColumnController` com os 5 endpoints
- [ ] Adaptar `WorkspaceService.create` e `createPersonalWorkspace` para semear colunas
- [ ] Adaptar `TaskService.resolvePositionForNewTask` (remover hardcode "PLANNED")
- [ ] Adicionar eventos WS de colunas no `WorkspaceEventPublisher`

### Fase 2 — Backend (IA)
- [ ] Adaptar `GeminiService` para buscar colunas dinamicamente no contexto de cada workspace

### Fase 3 — Frontend (Core)
- [ ] Criar hook `useWorkspaceColumns` (fetch + cache + WebSocket sync)
- [ ] Adaptar `DashboardPage` para estado dinâmico de colunas
- [ ] Adaptar `KanbanColumn` para receber `color` como prop
- [ ] Adaptar `KanbanSwimlane` para usar `columns` prop
- [ ] Adaptar `TaskFormModal` dropdown de status

### Fase 4 — Frontend (UI de Gerenciamento)
- [ ] Criar `ColumnsModal` (lista, DnD reorder, inline edit, color picker, delete com guard)
- [ ] Adicionar item "Colunas" na sidebar (Registros accordion, visível apenas para criador)
- [ ] Adicionar textos i18n (`sidebar.columns`, `columns.modal.*`)
- [ ] Sincronizar eventos WS de colunas no `useWorkspaceSocket`

---

## 8. Impacto e Riscos

| Item | Avaliação |
|---|---|
| **Compatibilidade de dados** | ✅ Seguro — backfill na migration cria colunas padrão para todos os workspaces existentes |
| **API backward compatibility** | ✅ Seguro — novos endpoints; endpoints de tasks não mudam |
| **Dados de tarefas existentes** | ✅ Seguro — status PLANNED/DOING/DONE já estão no banco; a migration cria as `WorkspaceColumn` correspondentes |
| **IA / Gemini** | ⚠️ Requer atenção — system prompt e tool schemas precisam ser dinamizados |
| **Workspaces existentes sem colunas** | ✅ Coberto pela migration de backfill |
| **Coluna excluída com tarefas** | 🔒 Bloqueado por validação no backend (retorna 409 Conflict) |
| **Performance** | ✅ Baixo impacto — colunas são poucos registros, carregados uma vez por troca de workspace |
