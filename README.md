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
- Retrieval robustness: **normalize + retry** (removes filler words/punctuations and “多少钱/是多少”等尾巴)
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
