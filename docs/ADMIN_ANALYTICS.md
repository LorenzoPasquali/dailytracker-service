# Painel interno de analytics (`/analytics`)

Dashboard oculto para o operador do Daily Tracker. Mostra aquisição (visitas na landing), conversão,
engajamento (DAU/WAU/MAU, retenção por coorte), ranking de usuários, distribuição por país e saúde do sistema.

## Acesso

- URL: `https://dailytracker.com.br/analytics` (rota lazy da SPA, sem link em lugar nenhum).
- Login: senha única em `ADMIN_PASSWORD` no `.env` do backend. Sem senha configurada, o login responde 401 sempre.
- Sessão: token HMAC próprio (não é o JWT de usuário), guardado em `sessionStorage`, expira em
  `ADMIN_TOKEN_TTL_MINUTES` (padrão 120). Fecha a aba, perde a sessão.
- Proteção: 5 tentativas por IP e 30 globais a cada 5 minutos no `POST /admin/login`; comparação
  de senha em tempo constante; `/admin/**` responde 401 sem token válido.

## Variáveis de ambiente (backend)

| Variável | Obrigatória | Descrição |
|----------|-------------|-----------|
| `ADMIN_PASSWORD` | sim, para usar o painel | Senha do painel |
| `ADMIN_TOKEN_SECRET` | recomendada | Segredo HMAC das sessões admin. Vazio = aleatório por boot (sessões caem ao reiniciar) |
| `ADMIN_TOKEN_TTL_MINUTES` | não | Validade da sessão admin (padrão 120) |
| `ANALYTICS_HASH_SALT` | recomendada | Salt do hash diário de visitante. Vazio = aleatório por boot (contagem de únicos reinicia) |
| `GEOIP_DB_PATH` | não | Caminho do `GeoLite2-Country.mmdb` (padrão `./data/GeoLite2-Country.mmdb`) |
| `MAXMIND_ACCOUNT_ID` | para geo | Conta MaxMind (GeoLite2 é gratuito, exige cadastro) |
| `MAXMIND_LICENSE_KEY` | para geo | License key da conta |

Sem `MAXMIND_*`, tudo funciona, só o país fica vazio (mapa e tabela por país ficam sem dados).

## Deploy (VPS, docker compose)

1. Adicionar as variáveis acima no `.env` do serviço.
2. Montar um volume para o banco GeoIP sobreviver a redeploys:
   ```yaml
   services:
     api:
       volumes:
         - geoip-data:/app/data
       environment:
         GEOIP_DB_PATH: /app/data/GeoLite2-Country.mmdb
   volumes:
     geoip-data:
   ```
3. No primeiro boot com credenciais, o app baixa o `.mmdb` em background (~7 MB) e recarrega o leitor.
   Atualiza toda segunda 04:00 (Brasília) se o arquivo tiver mais de 30 dias.
4. A migration `V16` cria as tabelas e preenche `User.createdAt` a partir do workspace pessoal.
   Usuários anteriores à migration V6 (workspaces) ficam todos com a mesma data (a do backfill de V6).

## O que é coletado

| Tabela | Origem | Conteúdo |
|--------|--------|----------|
| `pageview_event` | `POST /public/pageview` (beacon do frontend em páginas públicas) | path, referrer, utm, país, `visitor_hash` |
| `login_event` | `AuthService.login/register`, `OAuth2SuccessHandler` | user_id, `signup`/`login`, `email`/`google`, país |
| `user_activity_day` | `JwtAuthenticationFilter` (1 linha por usuário por dia) | user_id, dia (Brasília), país |
| `User.createdAt`, `User.signupCountry` | migration + evento de signup | data e país do cadastro |

Privacidade: nenhum IP é persistido. `visitor_hash = sha256(ip | user-agent | dia | salt)`,
então o mesmo visitante conta uma vez por dia e não é rastreável entre dias. Sem cookie, sem
identificador no navegador. O beacon roda independente do banner de cookies (contagem agregada
anônima).

Tudo é gravado fora da thread da requisição (`@Async` no executor `analyticsExecutor`). Falha
de analytics nunca quebra login, cadastro ou request; só gera `WARN` no log.

## Métricas e definições

- Dia = data no fuso `America/Sao_Paulo`.
- Visitante único = `visitor_hash` distinto no período.
- Conversão visita → conta = contas criadas no período / visitantes únicos no período.
- Usuário ativo = pelo menos uma chamada autenticada à API naquele dia. DAU = dia; WAU = 7 dias; MAU = 30 dias; stickiness = DAU/MAU.
- Funil: visitantes → chegaram em `/login` ou `/register` → contas criadas → onboarding concluído → criaram task → ativos 7 dias após o cadastro. Os três últimos contam usuários criados no período.
- Coorte = semana de cadastro (`User.createdAt`); célula S<n> = % da coorte com atividade na semana n após o cadastro.
- Ranking de usuários: ordena por dias ativos (30d), depois tasks. Histórico de atividade começa quando o painel foi publicado.

## Endpoints

| Método | Rota | Auth |
|--------|------|------|
| POST | `/public/pageview` | pública, 60 req/min por IP |
| POST | `/admin/login` | pública, rate limit |
| GET | `/admin/analytics/overview?from&to` | admin |
| GET | `/admin/analytics/engagement?from&to` | admin |
| GET | `/admin/analytics/users?limit` | admin |
| GET | `/admin/analytics/geo?from&to` | admin |
| GET | `/admin/analytics/health` | admin |

`from`/`to` no formato `yyyy-MM-dd`, padrão últimos 30 dias, máximo 366 dias.

## Código

- Backend: `com.dailytracker.api.analytics` (`admin/`, `capture/`, `geo/`, `query/`). Hooks de uma linha
  em `AuthService`, `AuthController`, `OAuth2SuccessHandler`, `JwtAuthenticationFilter`, `SecurityConfig`.
- Frontend: `src/pages/analytics/`, `src/services/adminApi.js` (axios separado, não mexe na sessão do usuário),
  `src/hooks/usePageviewTracking.js`, primitivos de gráfico em `src/components/charts/`.

## Limitações conhecidas

- Rate limiter e cache de dedupe são em memória: valem para uma instância. O upsert em
  `user_activity_day` é idempotente, então mais instâncias só geram escritas extras, não erro.
- Sem rollup: as consultas leem as tabelas brutas. Com volume alto (milhões de pageviews) vale
  agregar por dia num job.
- Textos do painel são fixos em pt-BR (uso interno).
