# Netease Cloud Music CS Agent: Minimal RAG Customer Support (Spring Boot + DashScope)

A minimal, reproducible **RAG-style customer support Agent** for NetEase Cloud Music.

**What it does**
- **Retrieve** top-5 relevant KnowledgeBase entries (H2 + Spring Data JPA)
- **Grounded answering** with a strict system policy: *answer only based on retrieved “Known Info”, otherwise refuse*
- **LLM backend**: Alibaba Bailian **DashScope (OpenAI-compatible endpoint)** via OkHttp + Spring Boot Jackson `ObjectMapper`
- **API**: `GET /api/agent/chat?question=...` → `{"answer":"...","hits":N}`

**Highlights (the “vibe coding” bits)**
- Minimal closed loop: **Controller → Retrieval → Prompt → LLM → JSON response**
- Strict anti-hallucination: if `hits == 0`, **no LLM call**, direct refusal
- Retrieval robustness: **normalize + retry** (removes filler words/punctuations)
- No extra JSON libs: **reuses Spring Boot Jackson** (`ObjectMapper`), no Gson/Fastjson
- Config via env var: `DASHSCOPE_API_KEY`

---

## Environment Setup

### Requirements
- JDK 17+
- Maven 3.8+
- DashScope API Key (Alibaba Bailian)

### 1) Set API Key (choose one)

**Windows (PowerShell)**
```powershell
setx DASHSCOPE_API_KEY "YOUR_KEY"
```

**macOS / Linus (Bash)**
```bash
export DASHSCOPE_API_KEY="YOUR_KEY"
```

### 2) Run
``` bash
mvn spring-boot:run
```
After startup:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 Console : `http://localhost:8080/h2`


## Quick Start
### 1) Minimal RAG Chat (Recommended)
**Step A — Ensure your KnowledgeBase has data**
Open Swagger UI and create at least 2 active entries that cover “自动续费/会员/黑胶”等问题.
Suggested example payloads (adjust fields to match your API if needed):

```json
{
  "question": "怎么取消会员自动续费",
  "answer": "进入 App【个人中心】->【会员中心】->【管理续费】-> 选择订阅并取消。",
  "keywords": "取消 自动续费 会员"
}
```
```json
{
  "question": "黑胶 VIP 多少钱",
  "answer": "价格以 App 会员页展示为准；支持包月/包季/包年，活动价格可能变化。",
  "keywords": "黑胶 价格 VIP 多少钱"
}
```
> Note: default DB is H2 in-memory. Restarting the app may clear data unless you seed it again.

**Step B — Call the Agent endpoint (3 questions for reproduction)**
```bash
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=取消自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消会员自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶多少钱"
```
Expected response shape:
```json
{"answer":"...","hits":2}
```
- `hits > 0` → LLM answers **based on Known Info**
- `hits = 0` fixed refusal: `抱歉，小云暂时还没学会这个问题`

### 2) Retrieval Debug Route (No LLM knowledge required)
This route is for debugging “why no hit?” quickly.
1) Check H2 Console:
  - URL: `http://localhost:8080/h2`
  - Confirm KnowledgeBase rows exist and `active=true`
3) Compare short keyword query vs longer natural question:
```bash
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶多少钱"
```
If shorter queries hit but longer ones don’t, common causes:
- KnowledgeBase `keywords/question` doesn’t contain core terms (or only exists in `answer`)
- normalization rules need to strip more suffix/prefix variants
- fuzzy query is too strict (only one direction contains match)


## API
### Chat (Minimal RAG)
**GET** `/api/agent/chat?question=...`
Response:
```json
{ "answer": "...", "hits": 2 }
```
- `hits`: number of KnowledgeBase hits (Top5)
- `hits = 0`: direct refusal (no LLM call)

## Methods
### Minimal Lexical RAG (JPA Fuzzy Search + Normalize Retry)
- Entry: `KnowledgeBaseService.searchTop5(question)`
  - search with original question
  - if empty → `normalizeQuestion()` then search again
  - TopK = 5
- Files:
  - `controller/AgentController.java`
  - `service/KnowledgeBaseService.java`
  - `repo/KnowledgeBaseRepository.java`
  - `entity/KnowledgeBase.java`

### DashScope OpenAI-Compatible Chat Client
- OkHttp POST: `{baseUrl}/chat/completions`
- Parse: `choices[0].message.content` with Jackson `ObjectMapper`
- Non-200 → throw exception with status code + body
- Files: `service/ai/DashScopeClient.java`


## Templates (Prompt Policy)

### System Policy (strict grounded)
- Persona: “网易云音乐智能客服小云”
- Must answer **only using Known Info**
- If insufficient: reply exactly `抱歉，小云暂时还没学会这个问题`
- No fabrication

### User Message (Known Info injection)
```text
已知信息：
[1] {answer1}
[2] {answer2}
...
用户问题：{question}
```

## DashScope Client Details
### Request (OpenAI compatible)
```json
{
  "model": "qwen-plus",
  "temperature": 0.3,
  "messages": [
    {"role": "system", "content": "<system message>"},
    {"role": "user", "content": "<known info + question>"}
  ]
}
```
### Response Parsing
Extract: `choices[0].message.content`

### Configuration (application.properties)
```properties
agent.llm.base-url=https://dashscope-intl.aliyuncs.com/compatible-mode/v1
agent.llm.api-key=${DASHSCOPE_API_KEY:}
agent.llm.model=qwen-plus
agent.llm.temperature=0.3
```

## Evaluation
### Smoke Test (3 questions)
``` bash
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=取消自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=怎么取消会员自动续费"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶多少钱"
```
Check:
- `hits` is reasonable (1~5)
- `answer` is grounded and doesn’t invent facts beyond Known Info
- unknown questions refuse exactly

### Retrieval Robustness Checklist
Try pairs:
- `黑胶` vs `黑胶价格是多少`？
- with punctuation / filler words / suffix `多少钱`/`是多少`/`怎么`/`如何`

``` bash
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶"
curl.exe -G "http://localhost:8080/api/agent/chat" --data-urlencode "question=黑胶价格是多少？"
```


## Model
This repo does **not** ship model weights.
- LLM: DashScope `qwen-plus` (configurable)
- Retrieval: lexical fuzzy match (P0 baseline)
- Output policy: strict grounded answering + refusal


## About
A Spring Boot “minimal RAG customer support agent” project built with:
- H2 + Spring Data JPA KnowledgeBase
- OkHttp + Jackson + DashScope (OpenAI-compatible)
- Swagger UI for fast iteration (“vibe coding” friendly)
Resources:
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- H2 Console: `http://localhost:8080/h2`
