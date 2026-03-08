# dailytracker-service

https://dailytracker.com.br

Backend da aplicação Daily Tracker, escrito em Java com Spring Boot. Fornece a API REST consumida pelo frontend em React.

## Stack

- Java 21
- Spring Boot 3.5
- Spring Security + JWT + Google OAuth2
- Spring Data JPA (Hibernate)
- PostgreSQL (Neon)
- Maven

## Estrutura

```
src/main/java/com/dailytracker/api/
├── config/         Configurações (CORS, DataSource, Security)
├── controller/     Endpoints REST
├── dto/request/    Objetos de entrada da API
├── entity/         Entidades JPA mapeadas ao banco
├── exception/      Exceções e handler global de erros
├── repository/     Interfaces de acesso ao banco
├── security/       JWT (geração, validação, filtro) e OAuth2
└── service/        Regras de negócio
```

## Endpoints

### Auth (públicos)

| Método | Rota | Descrição |
|--------|------|-----------|
| POST | /auth/register | Cria uma conta com email e senha |
| POST | /auth/login | Retorna um token JWT |
| GET | /auth/google | Inicia o fluxo de login com Google |

### API (requer token)

| Método | Rota | Descrição |
|--------|------|-----------|
| GET | /api/user/me | Retorna o usuário autenticado |
| GET | /api/tasks | Lista as tasks do usuário |
| POST | /api/tasks | Cria uma task |
| PUT | /api/tasks/:id | Atualiza uma task |
| DELETE | /api/tasks/:id | Remove uma task |
| GET | /api/projects | Lista os projetos do usuário |
| POST | /api/projects | Cria um projeto |
| PUT | /api/projects/:id | Atualiza um projeto |
| DELETE | /api/projects/:id | Remove um projeto |
| POST | /api/task-types | Cria um tipo de tarefa |
| PUT | /api/task-types/:id | Atualiza um tipo de tarefa |
| DELETE | /api/task-types/:id | Remove um tipo de tarefa |

### Healthcheck

GET /healthz retorna `OK`.

## Rodando localmente

Pré-requisitos: Java 21 e PostgreSQL rodando com o banco já criado.

```bash
DATABASE_URL="postgresql://user:pass@localhost:5432/daily_scrum?schema=public" \
JWT_SECRET="sua_chave_com_pelo_menos_32_caracteres" \
FRONTEND_URL="http://localhost:5173" \
GOOGLE_CLIENT_ID="seu_client_id" \
GOOGLE_CLIENT_SECRET="seu_client_secret" \
./mvnw spring-boot:run
```

O servidor sobe na porta 3000 por padrão.

## Variáveis de ambiente

| Variável | Descrição |
|----------|-----------|
| DATABASE_URL | URL do PostgreSQL (formato Prisma ou JDBC, ambos funcionam) |
| JWT_SECRET | Chave para assinar os tokens JWT (mínimo 32 caracteres) |
| FRONTEND_URL | URL do frontend (usado no CORS e no redirect do OAuth) |
| GOOGLE_CLIENT_ID | Client ID do Google OAuth2 |
| GOOGLE_CLIENT_SECRET | Client Secret do Google OAuth2 |
| PORT | Porta do servidor (padrão: 3000) |

## Deploy

O projeto inclui um Dockerfile multi-stage. No Render, basta criar um Web Service apontando para o repositório com runtime Docker. O health check path é `/healthz`.

## Banco de dados

O banco foi originalmente criado pelo Prisma (migração do backend anterior em Node.js). O Hibernate está configurado com `ddl-auto: validate`, ou seja, ele apenas valida que as entidades batem com as tabelas existentes. Ele não cria nem altera tabelas.

As tabelas no PostgreSQL usam nomes em PascalCase com aspas ("User", "Task", etc.) porque foram criadas assim pelo Prisma. As entidades JPA mapeiam esses nomes explicitamente.
