# Netease Cloud Music Intelligent Customer Support Agent (Minimal RAG)

![Java](https://img.shields.io/badge/Java-17-blue)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-blue)
![Database](https://img.shields.io/badge/Database-H2%20%2F%20MySQL-lightgrey)
![Cache](https://img.shields.io/badge/Cache-Redis-lightgrey)
![Docker](https://img.shields.io/badge/Docker-Compose-blue)
![License](https://img.shields.io/badge/License-MIT-grey)

A lightweight **Retrieval-Augmented Generation (RAG)** backend for high-volume customer support scenarios.

This service enforces a **grounded-only** answering policy:
- If retrieval returns **no hit** (`hits == 0`), it returns a fixed refusal message **without invoking the LLM** (fail-fast).
- If `hits > 0`, it injects retrieved KnowledgeBase entries as **Known Info** and constrains the LLM to answer **only** from that context.

---

## Project Background

Customer support queries in music streaming apps (e.g., membership renewal, pricing) are repetitive and high-volume.  
This project demonstrates a minimal RAG pipeline with production-minded engineering choices:

- **Predictable control flow:** synchronous end-to-end pipeline for stability
- **Hallucination containment:** refusal gate when retrieval has no hit
- **Performance optimization:** Redis caching for hot queries (with TTL and graceful degradation)
- **Environment separation:** H2 (dev) vs MySQL + Redis (prod simulation) via Spring Profiles
- **Vendor-agnostic LLM integration:** OpenAI-compatible protocol (DashScope/Qwen by default)

---

## Key Features

### 1. Strict Grounding Policy (Fail-Fast)
- **Hit = 0:** return fixed refusal immediately (**no LLM call**)
- **Hit > 0:** inject “Known Info” and answer **only** based on retrieved context

### 2. Dual-Profile Support (Dev vs Prod Simulation)
- **dev (default):** H2 in-memory, zero infrastructure required
- **prod:** MySQL persistence + Redis caching (Docker Compose), closer to real-world deployment

### 3) Redis Caching (Hot Query Optimization)
- **Cache-First Strategy:** Checks Redis before triggering retrieval or LLM inference to reduce latency and token costs.
- **Smart TTL:**
  - **Standard Answer:** Long TTL (e.g., 10 min) for high cache hit rate.
  - **Refusal (Hits=0):** Short TTL (e.g., 30s) to prevent "stale refusals" after KnowledgeBase updates.
- **Stability:** Redis failures are logged as warnings; the system automatically falls back to DB+LLM without breaking the user request.

### 4. Minimal Retrieval Baseline (Top-K)
- Top-K lexical retrieval (K=5) over KnowledgeBase (Spring Data JPA)
- Optional query normalization + retry to improve recall on noisy inputs

---

## Architecture

### Data Flow (Fail-Fast + Cache + RAG)

1. Input normalization (trim / simple cleanup)
2. Redis cache lookup (hot query optimization)
3. Top-K retrieval from KnowledgeBase (K=5)
4. Refusal gate: if `hits == 0`, return refusal (no LLM)
5. Prompt assembly: inject Known Info
6. LLM inference (DashScope OpenAI-compatible endpoint)
7. Write-back to Redis with TTL (Short TTL for refusals to avoid stale refusals)

```mermaid
flowchart LR
  U[User Question] --> C[AgentController]
  C --> KV{Redis Cache}

  %% Cache Hit Logic
  KV -- Hit --> Return[Return Cached]
  Return --> C

  %% Cache Miss Logic
  KV -- Miss --> R[Top-K Retrieval]

  %% Path 1: No Knowledge Found (Refusal)
  R -- Hits=0 --> Z[Refusal Msg]
  Z -- "Anti-Penetration" --> W1[Write: Short TTL]
  W1 --> KV
  Z --> C

  %% Path 2: Knowledge Found (RAG)
  R -- Hits>0 --> P[Build Prompt]
  P --> L[DashScope Chat]
  L -- "Standard Cache" --> W2[Write: Long TTL]
  W2 --> KV
  L --> C

  C --> U

```
---

## Prompt Policy (Grounded Answering)

The system uses a rigid template to prevent the LLM from using external knowledge.

```text
[System Role]
Persona: NetEase Cloud Music customer support agent
Constraint: Answer ONLY using the provided "Known Info".
Failure Case: If the info is insufficient, reply exactly:
"抱歉，小云暂时还没学会这个问题"
No fabrication allowed.

[User Role]
Known Info:
[1] <retrieved_answer_1>
[2] <retrieved_answer_2>
...
User Question: <question>
```

---

## API Reference

### Chat Interface
`GET /api/agent/chat?question=...`

Response:
```json
{
  "answer": "....",
  "hits": 2
}
```

Behavior:
- `hits = 0` → fixed refusal (no LLM call)
- `hits > 0` → LLM-generated answer grounded on Known Info

---

## Tech Stack

| Component | Choice | Description |
| --- | --- | --- |
| Language | Java 17 | Core development language |
| Framework | Spring Boot | Web MVC and dependency injection |
| ORM | Spring Data JPA | Repository abstraction over DB |
| Database (dev) | H2 | Zero-infra rapid development |
| Database (prod) | MySQL 8 | Persistence for production simulation |
| Cache (prod) | Redis 7 | Hot query caching with TTL |
| LLM Integration | OkHttp + Jackson | OpenAI-compatible chat completion client |
| API Docs | OpenAPI / Swagger UI | API exploration and testing |
| Deployment | Docker Compose | One-command infra startup |

---

## Repository Structure

- `controller/AgentController.java` — request entry, response shaping
- `service/KnowledgeBaseService.java` — retrieval + normalization retry
- `repo/KnowledgeBaseRepository.java` — JPA query layer
- `entity/KnowledgeBase.java` — KnowledgeBase schema
- `service/ai/DashScopeClient.java` — LLM client (OpenAI-compatible protocol)

---

## Configuration

### Environment Variables
- `DASHSCOPE_API_KEY` (required)

```bash
# macOS / Linux
export DASHSCOPE_API_KEY="your_api_key_here"

# Windows (PowerShell)
setx DASHSCOPE_API_KEY "your_api_key_here"
```

### Spring Profiles
Use `dev` by default and switch to `prod` when running with Docker.

**application.properties**
```properties
spring.profiles.default=dev
```

**application-dev.properties** (H2; keep your existing settings)
```properties
# H2 Config (example)
spring.datasource.url=jdbc:h2:mem:testdb
spring.datasource.driver-class-name=org.h2.Driver
spring.jpa.hibernate.ddl-auto=update
```

**application-prod.properties** (MySQL + Redis)
```properties
# MySQL (prod)
spring.datasource.url=jdbc:mysql://localhost:3306/cs_agent?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Singapore
spring.datasource.username=cs
spring.datasource.password=cs_pass
spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
spring.jpa.hibernate.ddl-auto=update

# Redis (prod)
spring.data.redis.host=localhost
spring.data.redis.port=6379

# Cache (prod)
agent.cache.enabled=true
agent.cache.ttl-seconds=600
agent.cache.refusal-ttl-seconds=30
```

---

## Getting Started

### 1. Rapid Development (Default: H2)
Zero infrastructure required.

```bash
# Windows
.\mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

After startup:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 Console: `http://localhost:8080/h2`

Seed a few KnowledgeBase entries via Swagger UI to reproduce retrieval hits.

Example KnowledgeBase payload:
```json
{
  "question": "怎么取消会员自动续费",
  "answer": "进入 App【个人中心】->【会员中心】->【管理续费】-> 选择订阅并取消。",
  "keywords": "取消 自动续费 会员"
}
```

Quick test:
```bash
curl -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消自动续费"
```

### 2. Production Simulation (Docker: MySQL + Redis)

1) Start infrastructure:
```bash
docker compose up -d
docker ps
```

2) Run with prod profile:

```bash
# Windows (Powershell)
.\mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=prod

# macOS / Linux
./mvnw spring-boot:run -Dspring-boot.run.profiles=prod
```

3) Cache behavior
- Cache **hit** returns immediately (no LLM call).
- Cache **miss** triggers retrieval; `hits==0` refuses without calling LLM.
- Cache write-back uses TTL:
  - normal answer: `agent.cache.ttl-seconds`
  - refusal: `agent.cache.refusal-ttl-seconds` (short TTL to avoid long-term refusal)
- Redis failures **do not break** the main flow (degrade to cache miss).

4) Verification (3 requests)
```bash
# macOS / Linux users may need to use 'curl' instead of 'curl.exe'
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=火星移民怎么报名"
```
Expected log patterns:
- 1st request: `cache=MISS` → `llm=CALL` → `cache=WRITE`
- 2nd request: `cache=HIT` (no `llm=CALL`)
- 3rd request: `cache=MISS` → `gate=REFUSAL hits=0 llm=SKIP`

5) Degradation drill (Redis down)
```bash
docker stop csagent-redis
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消自动续费"
docker start csagent-redis
```
Expected results: 
the API still returns normally; cache logs show miss and Redis errors are swallowed **(warn only)**.

---

## Docker Compose (Reference)

Create `docker-compose.yml` in project root:

```yaml
services:
  mysql:
    image: mysql:8.0
    container_name: csagent-mysql
    environment:
      MYSQL_DATABASE: cs_agent
      MYSQL_USER: cs
      MYSQL_PASSWORD: cs_pass
      MYSQL_ROOT_PASSWORD: root_pass
      TZ: Asia/Singapore
    ports:
      - "3306:3306"
    volumes:
      - mysql_data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "127.0.0.1", "-uroot", "-proot_pass"]
      interval: 5s
      timeout: 3s
      retries: 30

  redis:
    image: redis:7-alpine
    container_name: csagent-redis
    ports:
      - "6379:6379"
    volumes:
      - redis_data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 30

volumes:
  mysql_data:
  redis_data:
```


---

## Screenshots (Recommended)

Add real screenshots under `docs/` to improve scanability:

- `docs/swagger.png`
- `docs/response.png`

Example:

```markdown
## Screenshots

### Swagger UI
![Swagger UI](docs/swagger.png)

### Example Response
![Response](docs/response.png)
```

---

## License
MIT

---

## Author
Chen Hongshan
