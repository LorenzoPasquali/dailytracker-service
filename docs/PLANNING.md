# DailyTracker Service — Planejamento de Implementação

> Documento de referência para continuidade entre sessões de desenvolvimento.
> Atualizado em: 2026-03-08

---

## Visão Geral do Projeto

**DailyTracker** é um sistema de gerenciamento de tarefas com quadro Kanban e assistente de IA integrado. O backend é uma API REST em Spring Boot 3 + Java 21, conectada a um banco PostgreSQL, com autenticação JWT + OAuth2 Google e integração com a API Gemini (Google GenAI).

---

## Arquitetura Atual

### Stack
- **Java 21** / **Spring Boot 3**
- **PostgreSQL** via JPA/Hibernate + Flyway (migrations)
- **Spring Security** com JWT + OAuth2 Google
- **Google GenAI SDK** (`com.google.genai`) para chat com IA
- **Jackson** para serialização JSON
- **Lombok** para redução de boilerplate
- **Maven** como build tool

### Estrutura de Pacotes

```
com.dailytracker.api
├── config/           # SecurityConfig, CorsConfig, DataSourceConfig, I18nConfig, PasswordConfig
├── controller/       # AuthController, TaskController, ProjectController,
│                     # TaskTypeController, UserController, AiController, HealthController
├── dto/
│   ├── request/      # LoginRequest, RegisterRequest, TaskRequest, TaskUpdateRequest,
│   │                 # ProjectRequest, TaskTypeRequest, ChatRequest, TokenRefreshRequest,
│   │                 # LanguageRequest
│   └── response/     # AuthResponse, ChatResponse
├── entity/           # User, Task, Project, TaskType, RefreshToken
├── exception/        # BadRequestException, ResourceNotFoundException, GlobalExceptionHandler
├── i18n/             # MessageService, UserAwareLocaleResolver
├── repository/       # UserRepository, TaskRepository, ProjectRepository,
│                     # TaskTypeRepository, RefreshTokenRepository
├── security/         # JwtService, JwtAuthenticationFilter, OAuth2SuccessHandler
└── service/          # AuthService, TaskService, ProjectService, TaskTypeService,
                      # GeminiService, EncryptionService
```

### Entidades e Banco de Dados

#### User
| Campo       | Tipo    | Notas                          |
|-------------|---------|--------------------------------|
| id          | Integer | PK, auto-increment             |
| email       | String  | unique, not null               |
| password    | String  | nullable (OAuth2 não tem senha)|
| googleId    | String  | unique, nullable               |
| geminiKey   | String  | encrypted (AES), nullable      |
| language    | String  | default "pt-BR"                |

#### Task
| Campo       | Tipo    | Notas                          |
|-------------|---------|--------------------------------|
| id          | Integer | PK                             |
| title       | String  | not null                       |
| description | String  | nullable                       |
| status      | String  | PLANNED / DOING / DONE         |
| createdAt   | Instant | auto-set no @PrePersist        |
| updatedAt   | Instant | auto-set no @PreUpdate         |
| userId      | Integer | FK → User                      |
| projectId   | Integer | FK → Project, nullable         |
| taskTypeId  | Integer | FK → TaskType, nullable        |

#### Project
| Campo  | Tipo    | Notas               |
|--------|---------|---------------------|
| id     | Integer | PK                  |
| name   | String  | not null            |
| color  | String  | default "#8b949e"   |
| userId | Integer | FK → User           |

#### TaskType
| Campo     | Tipo    | Notas          |
|-----------|---------|----------------|
| id        | Integer | PK             |
| name      | String  | not null       |
| projectId | Integer | FK → Project   |

#### RefreshToken
| Campo     | Tipo      | Notas                     |
|-----------|-----------|---------------------------|
| id        | Integer   | PK                        |
| token     | String    | unique                    |
| userId    | Integer   | FK → User                 |
| expiresAt | Instant   | usado para validação      |

### Migrations Flyway (em ordem)
- `V1` — adiciona coluna `geminiKey` em User
- `V2` — cria tabela `RefreshToken`
- `V3` — adiciona coluna `language` em User

---

## Endpoints da API

### Auth (`/auth`)
| Método | Path                    | Descrição                        |
|--------|-------------------------|----------------------------------|
| GET    | /auth/google            | Redireciona para OAuth2 Google   |
| POST   | /auth/register          | Registro com email/senha         |
| POST   | /auth/login             | Login, retorna JWT + refreshToken|
| POST   | /auth/refresh           | Troca refreshToken por novo JWT  |

### Tasks (`/api/tasks`) — requer JWT
| Método | Path            | Descrição                      |
|--------|-----------------|--------------------------------|
| GET    | /api/tasks      | Lista todas as tarefas do user |
| POST   | /api/tasks      | Cria tarefa                    |
| PUT    | /api/tasks/{id} | Atualiza tarefa                |
| DELETE | /api/tasks/{id} | Deleta tarefa                  |

### Projects (`/api/projects`) — requer JWT
| Método | Path                | Descrição              |
|--------|---------------------|------------------------|
| GET    | /api/projects       | Lista projetos do user |
| POST   | /api/projects       | Cria projeto           |
| PUT    | /api/projects/{id}  | Atualiza projeto       |
| DELETE | /api/projects/{id}  | Deleta projeto         |

### Task Types (`/api/task-types`) — requer JWT
| Método | Path                    | Descrição               |
|--------|-------------------------|-------------------------|
| GET    | /api/task-types         | Lista tipos do user     |
| POST   | /api/task-types         | Cria tipo               |
| PUT    | /api/task-types/{id}    | Atualiza tipo           |
| DELETE | /api/task-types/{id}    | Deleta tipo             |

### User (`/api/user`) — requer JWT
| Método | Path               | Descrição                    |
|--------|--------------------|------------------------------|
| GET    | /api/user/me       | Retorna dados do usuário     |
| PUT    | /api/user/language | Atualiza idioma do usuário   |

### AI (`/api/ai`) — requer JWT
| Método | Path            | Descrição                                           |
|--------|-----------------|-----------------------------------------------------|
| GET    | /api/ai/key-status | Verifica se usuário tem chave Gemini configurada |
| PUT    | /api/ai/key     | Salva chave Gemini (criptografada com AES)          |
| DELETE | /api/ai/key     | Remove chave Gemini                                 |
| POST   | /api/ai/chat    | Envia mensagem ao assistente IA                     |

**POST /api/ai/chat — Request:**
```json
{
  "history": [
    { "role": "user", "text": "quais são minhas tarefas?" }
  ]
}
```

**POST /api/ai/chat — Response:**
```json
{
  "reply": "Aqui estão suas tarefas...",
  "history": [
    { "role": "user", "text": "quais são minhas tarefas?" },
    { "role": "model", "text": "Aqui estão suas tarefas...", "parts": "..." }
  ]
}
```
> O frontend deve reenviar o `history` retornado em cada nova mensagem para manter o contexto da conversa.

---

## Módulo de IA — GeminiService

### Fluxo de Execução
```
POST /api/ai/chat
  → AiController.chat()
    → descriptografa chave Gemini do user
    → GeminiService.chat(apiKey, history, userId, language)
      → buildContents(history)       # converte histórico em List<Content>
      → buildConfig(language)        # system prompt + tools declarados
      → generateContent()            # primeira chamada ao Gemini
      → loop (max 3 iterações):
          se há FunctionCalls na resposta:
            → adiciona model turn ao histórico
            → executeTool() para cada function call
            → adiciona user turn com FunctionResponses ao histórico
            → generateContent() novamente
      → addContentToHistory(history, finalContent)
      → retorna ChatResponse(reply, history)
```

### Ferramentas Declaradas ao Gemini
| Ferramenta      | Descrição                                           | Parâmetros                                                        |
|-----------------|-----------------------------------------------------|-------------------------------------------------------------------|
| `get_tasks`     | Busca tarefas do usuário com filtros opcionais      | `date`, `startDate`, `endDate`, `status`, `project`               |
| `get_projects`  | Lista todos os projetos do usuário                  | nenhum                                                            |
| `get_task_types`| Lista todos os tipos de tarefa do usuário           | nenhum                                                            |
| `create_task`   | Cria uma nova tarefa no banco                       | `title` (obrigatório), `description`, `status`, `projectName`, `taskTypeName` |

### System Prompts
Suportados nos idiomas: `pt-BR`, `en-US`, `es`

Cada prompt contém:
- Descrição do sistema (Kanban, 3 colunas: PLANNED/DOING/DONE)
- Data/hora atual injetada dinamicamente (fuso: America/Sao_Paulo)
- Instruções de papel e casos de uso (daily scrum, análise de produtividade)
- Mapeamento de vocabulário do usuário → status
- **Regras de formatação obrigatórias:**
  - NUNCA usar `*` como bullet — usar `-`
  - NUNCA usar `**` para negrito
  - Formato de tarefa: `"Título (Projeto: Nome, Tipo: Tipo)"`
  - Resumo de daily organizado por status (Feito, Em Progresso, Planejado)
  - Nunca expor IDs internos

### Serialização do Histórico
O histórico é armazenado como `List<Map<String, String>>` onde cada entry tem:
- `role` — "user" ou "model"
- `text` — texto legível (quando disponível)
- `parts` — JSON serializado dos `Part[]` do Gemini (preserva function calls/responses)

Isso garante que o contexto das tool calls seja preservado entre turnos.

---

## Internacionalização (i18n)

- Mensagens de erro em `messages_pt_BR.properties`, `messages_en_US.properties`, `messages_es.properties`
- `UserAwareLocaleResolver` resolve o locale com base no campo `language` do usuário autenticado
- `MessageService` é injetado nos services/controllers para obter mensagens traduzidas

---

## Segurança

- **JWT**: expiração 24h, refresh token 30 dias
- **OAuth2 Google**: callback em `/auth/google/callback`, redireciona ao frontend com token
- **Chave Gemini**: criptografada com AES antes de persistir (`EncryptionService`)
- **CORS**: configurado via `CorsConfig`, origin permitida via `FRONTEND_URL`
- Todas as rotas `/api/**` exigem autenticação; `/auth/**`, `/actuator/health` são públicas

---

## Variáveis de Ambiente Necessárias

| Variável           | Descrição                                     |
|--------------------|-----------------------------------------------|
| `DATABASE_URL`     | URL JDBC do PostgreSQL                        |
| `JWT_SECRET`       | Secret para assinar JWT                       |
| `GOOGLE_CLIENT_ID` | OAuth2 Google Client ID                       |
| `GOOGLE_CLIENT_SECRET` | OAuth2 Google Client Secret             |
| `AES_SECRET`       | Chave AES para criptografar chave Gemini      |
| `FRONTEND_URL`     | URL do frontend (default: http://localhost:5173) |
| `PORT`             | Porta do servidor (default: 3000)             |

---

## O que já foi implementado (histórico recente)

| Data       | Feature                        | Commit                     |
|------------|--------------------------------|----------------------------|
| 2026-03-08 | Instrução de formatação da IA  | `fix: ai better instructions` |
| 2026-03-xx | i18n completo (3 idiomas)      | `feat: add i18n`           |
| 2026-03-xx | Remove projetos vazios         | `fix: remove empty projects` |
| 2026-03-xx | Fix bug OAuth2 expiration date | `fix: OAuth2 expiration date bug` |
| 2026-03-xx | Push & refresh notifications   | `feat: add push & refresh notifications` |

### Detalhes da última sessão (2026-03-08)
1. **Memória do chat (contexto)**: `ChatResponse` criado retornando `reply + history`. O histórico inclui `parts` serializados em JSON para preservar function calls/responses entre turnos.
2. **Formatação da IA**: System prompts atualizados para proibir `*` como bullet e `**` para negrito. Instrução obrigatória para usar `-` em listas.
3. **ChatRequest**: aceita `List<Map<String, String>> history` — o frontend envia o histórico completo a cada mensagem.

---

## Próximas Melhorias Identificadas (Backlog)

### Curto prazo
- [x] **IA criar tarefas**: ferramenta `create_task` implementada com fluxo conversacional (confirma lista → pergunta projeto → pergunta tipo → pergunta status → salva)
- [ ] **Testes de integração**: configurar ambiente de teste com H2 ou Testcontainers para rodar os testes automáticos sem depender do banco de produção
- [ ] **Validação de histórico no chat**: limitar tamanho máximo do histórico enviado para evitar payloads gigantes
- [ ] **Rate limiting na rota /api/ai/chat**: evitar uso excessivo da API Gemini por usuário

### Médio prazo
- [ ] **Filtro de tarefas por período no repositório**: mover a lógica de filtro de data do Java para SQL (query JPA) para performance
- [ ] **Suporte a mais idiomas na IA**: adicionar `fr`, `de` etc.
- [ ] **Histórico de conversas persistido**: salvar conversas no banco em vez de depender do cliente reenviar o histórico

### Longo prazo
- [ ] **Multi-tenant por workspace**: permitir que um usuário tenha múltiplos workspaces
- [ ] **Relatórios exportáveis**: gerar PDF/CSV com resumo de produtividade
- [ ] **Webhooks**: notificar sistemas externos quando tarefas mudam de status

---

## Convenções do Projeto

- Entidades JPA usam nomes de tabela/coluna entre aspas duplas (`"Task"`, `"createdAt"`) para respeitar case-sensitivity do PostgreSQL
- Status das tarefas são strings: `"PLANNED"`, `"DOING"`, `"DONE"` (sem enum Java, validado pelo frontend/IA)
- Respostas dos controllers usam `Map<String, Object>` em vez de DTOs dedicados (exceto Auth e Chat)
- O `userId` é extraído do JWT via `auth.getPrincipal()` nos controllers
- Flyway gerencia todas as alterações de schema — nunca usar `ddl-auto: update`
