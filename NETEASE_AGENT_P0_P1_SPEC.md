# NetEase Cloud Music Support Agent — P0/P1 生产就绪改造规格

> **交付对象**：coding agent
> **执行方式**：严格按 Task 顺序执行。每个 Task 结束必须跑 `./mvnw -B verify` 并确认通过后才进入下一个 Task。
> **分支策略**：所有工作在 `feat/production-readiness` 分支上进行。`main` 分支在任何时刻都必须保持可编译、测试通过状态（简历会链接到这个仓库）。每个 Task 完成后单独 commit，commit message 用 `[P0-1] ...` 这种前缀标注对应任务号。

---

## 0. 环境与版本约束（必读，先看这一节）

**技术栈现状**：Java 17 · Spring Boot 4.0.1 · Spring Data JPA · H2(dev)/MySQL(prod) · Redis · OkHttp · springdoc OpenAPI · Maven Wrapper · docker-compose

### ⚠️ 关键风险：不要套用 Spring Boot 3.x 的文档

Spring Boot 4.0 是大版本升级（Spring Framework 7 / Jakarta EE 11 基线），以下内容**必须先验证再写**，不要凭记忆或凭 Boot 3 教程直接写：

1. **所有新增依赖的 groupId/artifactId/version**：不要手写版本号，一律通过 `spring-boot-dependencies` BOM 管理。加依赖后先 `./mvnw -B dependency:tree` 确认解析成功，再写代码。
2. **Actuator / Micrometer 的坐标与配置项名称**：Boot 4 中部分 auto-configuration 模块做了拆分，`management.*` 配置项名称可能变化。先加依赖启动服务，访问 `/actuator` 看实际暴露了什么，再配置。
3. **Resilience4j 不要用**：`resilience4j-spring-boot3` starter 与 Boot 4 的兼容性不确定。本规格中的重试与熔断**全部手写**（见 Task 5），这也是刻意的——手写的代码在面试里比"我用了个库"好讲十倍。
4. **Jakarta 命名空间**：所有 servlet 相关 import 是 `jakarta.servlet.*`，不是 `javax.servlet.*`。

如果某个依赖在 Boot 4.0.1 下确实装不起来，**不要降级 Spring Boot 版本**（会引发连锁问题），改用手写实现，并在该 Task 的 commit message 里说明。

### 通用编码约束

- 不引入 Lombok（如果项目当前没用）。
- 不改动现有 API 的 URL 路径和响应字段名（除本规格明确要求的新增字段）。
- 所有新增配置项写进 `application.yml`/`application.properties`，并提供合理默认值，`dev` profile 下必须**零外部依赖可启动**（H2 内存库 + 缓存关闭 + LLM 走 stub）。
- 任何密钥都走环境变量，沿用现有 `${DASHSCOPE_API_KEY:}` 的模式，禁止硬编码。

---

## Task 1 — 【P0，前置阻塞项】可测试性重构

**目标**：把 `AgentController` 从"什么都干"变成"只做 HTTP 适配"，让业务链路可以被单元测试和被切面拦截。这是 Task 2/5/6/7 的前提。

### 1.1 抽出 LLM 客户端接口

新建 `service/llm/LlmClient.java`：

```java
public interface LlmClient {
    /** @throws LlmException 调用失败（含超时、非 2xx、解析失败） */
    String complete(String systemPrompt, String userPrompt);
}
```

新建 `service/llm/LlmException.java`（继承 `RuntimeException`），必须携带两个字段：

- `boolean retryable` — 是否可重试
- `Integer httpStatus` — 上游返回的状态码，网络层异常时为 `null`

把现有 OkHttp + DashScope 调用逻辑搬进 `service/llm/DashScopeLlmClient.java`（`@Component`，`@Profile("!test-stub")` 或用配置开关控制）。

**retryable 的判定规则（重要，Task 5 依赖它）**：

| 情况 | retryable |
|---|---|
| `IOException` / `SocketTimeoutException`（网络层） | `true` |
| HTTP 429 | `true` |
| HTTP 5xx | `true` |
| HTTP 408 | `true` |
| HTTP 400 / 401 / 403 / 404 及其他 4xx | **`false`** |
| 响应体 JSON 解析失败 | `false` |

新建 `service/llm/StubLlmClient.java`：返回固定文本，支持通过配置项 `agent.llm.stub.delay-ms`（默认 0）注入固定延迟。`dev` profile 和压测时使用。用配置项 `agent.llm.provider=dashscope|stub` 切换，通过 `@ConditionalOnProperty` 装配。

### 1.2 抽出编排服务

新建 `service/ChatService.java`，把 `AgentController.java:54-126` 的整条链路搬进来，方法签名：

```java
public ChatResult chat(String question);
```

`ChatResult` 是新的 record/DTO，字段：`String answer`、`int hits`、`boolean refused`、`boolean degraded`、`boolean cached`。

搬迁后的链路顺序**保持不变**：
1. 缓存查询（key = `agent:chat:v1:` + sha256(question)）
2. `KnowledgeBaseService.searchTop5`，未命中则 `normalizeQuestion()` 后二次检索
3. **拒答闸门**：hits == 0 → 直接返回 `refused=true`，**绝不调用 `LlmClient`**，以 30s TTL 缓存
4. 拼 prompt
5. 调 `LlmClient.complete(...)`
6. 写缓存（TTL 600s），返回

`AgentController` 缩减为：接参 → 调 `ChatService` → 包 `ResponseEntity`。控制器里不允许再出现任何检索、拼 prompt、调 LLM 的代码。

### 1.3 顺手清理死代码

`KnowledgeBaseService.searchFuzzy` 目前写了但从未被调用。**删掉它**（不要接上——接上会改变检索行为，超出本次范围；而留着死代码会在 code review 里扣分）。如果删除导致测试或编译问题，改为接入 `searchTop5` 的二次检索路径，二选一，不允许保留未被引用的方法。

### ✅ Task 1 验收标准

- [ ] `./mvnw -B verify` 通过
- [ ] `AgentController` 行数显著下降，且不 import 任何 OkHttp / LLM 相关类
- [ ] `grep -rn "searchFuzzy" src/` 无结果，或该方法被实际调用
- [ ] `dev` profile 下 `agent.llm.provider=stub`，无需任何环境变量即可 `./mvnw spring-boot:run` 启动，且 `/api/agent/chat?question=会员多少钱` 能返回
- [ ] 现有所有 API 的响应结构未发生 breaking change

---

## Task 2 — 【P0】测试套件

**目标**：从 1 个空 `contextLoads` 变成一组能证明关键行为的测试。**不追求覆盖率数字**，只写下面这五组。

用 `spring-boot-starter-test`（含 JUnit 5 + Mockito + AssertJ + MockMvc），确认它已在 `pom.xml` 中且 scope 为 `test`。

### 2.1 `NormalizeQuestionTest`（纯单元测试，不加载 Spring 容器）

覆盖以下 case，每个 case 一个 `@ParameterizedTest` 参数或独立测试方法：

- 标点去除：`"会员多少钱？"` → 不含 `？`
- 口语词去除：`"请问会员怎么取消续费"` → 不含 `请问`/`怎么`
- 空字符串输入 → 返回空字符串，不抛异常
- `null` 输入 → 明确定义行为（返回空字符串或抛 `IllegalArgumentException`），并断言该行为
- 纯标点输入 `"？？？"` → 返回空字符串
- 已规范化的输入 → 幂等（`normalize(normalize(x)) == normalize(x)`）
- 中英混合 + 全角/半角标点混合

### 2.2 `RefusalGateTest` ⭐ 旗舰测试

这是整个测试套件里最有分量的一个，面试会拿它讲，写仔细。

```
given: KnowledgeBaseService（@MockBean 或构造注入 mock）对任何输入都返回空列表
       LlmClient 为 mock
when:  chatService.chat("完全不存在的问题")
then:  1. 结果 refused == true
       2. answer 包含预设拒答话术
       3. verify(llmClient, never()).complete(any(), any())   ← 核心断言
       4. 缓存被写入，且 TTL == 30s（用 ArgumentCaptor 捕获 TTL 参数断言）
```

同时写一个反向 case：检索有命中时，`verify(llmClient, times(1)).complete(...)`。两个 case 一起才构成完整证明。

### 2.3 `CacheDegradationTest`（对应 README 的 Degradation Drill）

三个 case：

- `RedisCacheService.get(...)` 抛 `RuntimeException` → 服务按 cache miss 继续走完整链路，返回 200 和正确答案
- `RedisCacheService.set(...)` 抛 `RuntimeException` → 答案仍正常返回，异常被吞掉不外泄
- Redis 抛异常时不影响拒答闸门行为

**断言重点**：异常不能冒泡到 controller 层。用 MockMvc 断言 HTTP 200。

### 2.4 `AgentControllerWebTest`（`@WebMvcTest` + mock `ChatService`）

- happy path：200，响应体含 `answer` 和 `hits`
- 拒答 path：200，`hits == 0`
- 缺少 `question` 参数 → 400
- `question` 为空字符串或超长（>500 字符）→ 明确定义行为并断言（建议 400）

### 2.5 `KnowledgeBaseRepositoryTest`（`@DataJpaTest`，跑 H2）

- 软删除：`active=false` 的记录不出现在 `searchTop5` 结果中
- LIKE 匹配：能命中 `data.sql` 中的种子数据
- 通配符注入：`question` 含 `%` 或 `_` 时不会匹配到全部记录（**如果发现会，这是一个真实 bug，修掉并在 commit message 里注明**）

### ✅ Task 2 验收标准

- [ ] `./mvnw -B verify` 通过，测试数 ≥ 20
- [ ] 上述五个测试类全部存在且非空
- [ ] `RefusalGateTest` 中存在 `never()` 断言
- [ ] 所有测试在**无 Redis、无 MySQL、无 DASHSCOPE_API_KEY** 的环境下可跑通（这是 CI 的前提）
- [ ] 单次 `./mvnw -B verify` 耗时 < 90 秒

---

## Task 3 — 【P0】GitHub Actions CI

新建 `.github/workflows/ci.yml`：

- 触发：`push` 到任意分支 + `pull_request` 到 `main`
- runner：`ubuntu-latest`
- 步骤：checkout → `actions/setup-java@v4`（distribution `temurin`，java-version `17`，`cache: maven`）→ `./mvnw -B verify`
- 显式设置 `SPRING_PROFILES_ACTIVE=test`（或 `dev`），确保不依赖任何 service container
- 上传测试报告：`actions/upload-artifact`，路径 `target/surefire-reports/`，`if: always()`

**不要**在 CI 里起 MySQL/Redis service container——Task 2 已保证测试零外部依赖，加 container 只会拖慢并引入不稳定。

README 顶部加 CI badge（`![CI](https://github.com/<user>/<repo>/actions/workflows/ci.yml/badge.svg)`）。

### ✅ Task 3 验收标准

- [ ] push 后 Actions 页面显示绿色
- [ ] README 有 badge 且渲染正常
- [ ] 故意写一个失败的断言 push 一次，确认 CI 变红，然后改回来（验证 CI 真的在跑，不是空转）

---

## Task 4 — 【P0】性能基准测试

**目标**：拿到真实、可复现、可以写进简历的数字。**禁止编造数字。**

### 4.1 基准脚本

新建 `benchmarks/bench.py`（纯标准库 + `urllib`，或允许用 `httpx`；不要引入重型框架）。要求：

- 参数：`--url`、`--concurrency`、`--requests`、`--scenario`
- 记录每个请求的 wall-clock 延迟，输出 **p50 / p90 / p95 / p99 / mean / max / throughput(req/s) / 错误数**
- 先跑 warmup（不计入统计），默认 warmup 请求数 = 总数的 10%
- 结果以 markdown 表格输出到 stdout，同时写入 `docs/benchmarks.md`

### 4.2 必须测量的三条路径

| scenario | 请求内容 | 配置 | 说明 |
|---|---|---|---|
| `refusal` | 检索必然 0 命中的问题（如随机 UUID 字符串） | prod profile，Redis 开 | fail-fast 路径，不调 LLM |
| `cache_hit` | 固定同一个已知会命中的问题，warmup 已把它写入缓存 | prod profile，Redis 开 | 缓存命中路径 |
| `llm_path` | 每次请求带唯一后缀使缓存必然 miss，但检索仍有命中 | prod profile | 完整链路 |

### 4.3 关于 LLM 路径的两次测量（重要，别搞混）

`llm_path` 要分开测两次，**两个数字用途不同**：

- **真实延迟**：`agent.llm.provider=dashscope`，**串行**（concurrency=1），`--requests 20`。这个数字反映真实用户体验，但样本小、且会消耗 API 额度。记录时必须注明 `n=20, sequential, real DashScope`。
- **吞吐/并发**：`agent.llm.provider=stub` 且 `agent.llm.stub.delay-ms=800`（用真实测量的中位数填），`--concurrency 20 --requests 500`。这个数字反映服务本身的并发承载能力，与上游无关。记录时注明 `stubbed upstream, 800ms injected delay`。

### 4.4 `docs/benchmarks.md` 必须包含的元数据

不写这些的数字是不可信的，面试官会问：

- 测量日期
- 硬件（CPU 型号、核数、内存）
- Java 版本、Spring Boot 版本
- profile 与关键配置项取值
- 每个 scenario 的 n、concurrency、warmup 数
- LLM 是真实调用还是 stub（stub 则注明注入延迟）
- 复现命令（完整的 `python benchmarks/bench.py ...` 命令行）

README 里加一节 "Benchmarks"，贴出汇总表并链接到 `docs/benchmarks.md`。

### ✅ Task 4 验收标准

- [ ] `docs/benchmarks.md` 存在，含三条路径的完整数字与全部元数据
- [ ] 脚本可被重复执行并得到相近结果（跑两遍，p95 差异 < 30%）
- [ ] refusal 路径的 p95 明显低于 llm 路径（预期差一到两个数量级）——**如果不是，说明拒答闸门没生效，回去查 Task 1 的重构**
- [ ] README 有 Benchmarks 一节

> 🔖 **CHECKPOINT A** — 到这里 P0 完成。此时可以安全地更新简历第 1、3 条 bullet 并把真实 p95 数字填进去。如果今天时间不够，**停在这里投递也完全站得住**。

---

## Task 5 — 【P1】超时 · 退避重试 · 熔断（全部手写）

**目标**：让"上游不可靠"变成一个被显式管理的工程问题。这是本次改造中与支付业务最同构的部分。

### 5.1 显式超时

检查 `DashScopeLlmClient` 里的 `OkHttpClient` 构建。**如果吃的是 OkHttp 默认值，这是一个隐藏 bug**（默认 read timeout 10s，上游慢的时候线程会被占住 10 秒）。

改为从配置读取，默认值：

```yaml
agent:
  llm:
    timeout:
      connect-ms: 2000
      read-ms: 8000
      write-ms: 2000
      call-ms: 12000     # 整体调用上限，含重定向与重试
```

`callTimeout` 是兜底，必须设置。同时配置 `ConnectionPool`（maxIdleConnections=5, keepAlive=5min），`OkHttpClient` 必须是**单例**（`@Bean`），不要每次请求 new 一个。

### 5.2 退避重试

新建 `service/resilience/RetryExecutor.java`，不依赖任何外部库：

```java
public <T> T execute(String dependencyName, Supplier<T> call, long deadlineEpochMillis);
```

策略：

- 最多 **3 次总尝试**（初次 + 2 次重试），可配置 `agent.resilience.retry.max-attempts`
- **只在 `LlmException.retryable == true` 时重试**；`retryable == false` 立即抛出，不浪费一次尝试
- 退避算法：**full jitter** — `sleep = random(0, min(cap, base * 2^attempt))`，`base=200ms`，`cap=2000ms`
  - ⚠️ 必须是 full jitter，不是固定 `base * 2^attempt`。固定退避会造成重试风暴（thundering herd），这是面试考点，代码里写注释说明
- 上游返回 429 且带 `Retry-After` 头 → 优先采用该值（秒），但不超过 `cap`
- **deadline 检查**：每次重试前判断 `now + expectedBackoff + readTimeout > deadline`，若超出则**放弃重试直接抛出**。理由：重试到最后总预算已经花光，客户端早已超时断开，继续重试只是白烧上游配额
- 每次尝试记录日志：`dependencyName`、attempt 序号、异常类型、本次退避时长

### 5.3 熔断器

新建 `service/resilience/CircuitBreaker.java`，三态机：

```
CLOSED ──(失败率超阈值)──> OPEN ──(openDuration 到期)──> HALF_OPEN
   ↑                                                        │
   └──────────(探测全部成功)─────────────────────────────────┘
                                    │
                      (任一探测失败) └──> OPEN（重置计时器）
```

参数（全部可配置，给出默认值）：

| 配置项 | 默认 | 说明 |
|---|---|---|
| `sliding-window-size` | 20 | 计数型滑动窗口，记录最近 N 次结果 |
| `minimum-calls` | 10 | 窗口内样本不足时不做判定（避免冷启动误熔断） |
| `failure-rate-threshold` | 50 | 失败率百分比 |
| `open-duration-ms` | 30000 | OPEN 持续时长 |
| `half-open-permitted-calls` | 3 | HALF_OPEN 允许的探测请求数 |

**实现要点**：

- 线程安全：状态转换用 `ReentrantLock` 保护；滑动窗口用固定长度 `boolean[]` + 环形游标，读写都在锁内
- HALF_OPEN 的并发控制：用 `AtomicInteger` 计数已放行的探测请求，超过 `half-open-permitted-calls` 的请求直接按 OPEN 处理拒绝。**不要**放行全部请求（那样等于没有半开）
- OPEN 状态下调用 → 抛 `CircuitOpenException`，**不消耗任何上游资源、不 sleep**
- 只有 `retryable == true` 的失败才计入失败统计。上游返回 400（我们自己的 prompt 拼错了）不应该导致熔断——这个区分很重要，写注释说明

### 5.4 接线与降级响应

在 `ChatService` 中，LLM 调用包成：`circuitBreaker.execute(() -> retryExecutor.execute(...))`（熔断在外层，重试在内层）。

捕获 `CircuitOpenException` 和重试耗尽后的 `LlmException`，返回降级结果：

- `ChatResult.degraded = true`，`answer` 为降级话术（如"小云暂时无法回答，请稍后再试"）
- HTTP 状态码：**503 Service Unavailable**，并带 `Retry-After: 30` 响应头
  - 设计理由（写进 README）：客户端需要能区分"我不知道这个问题"（200 + 拒答）和"我暂时坏了"（503）。前者重试无意义，后者重试有意义。混成 200 会让调用方无法正确决策
- ⚠️ **降级响应绝对不能写进正常答案缓存**。否则一次上游抖动会把错误答案锁在缓存里 600 秒——这是典型的缓存投毒 bug。代码里加注释标注这一点

### 5.5 测试

新增 `RetryExecutorTest` 和 `CircuitBreakerTest`：

- retryable 异常 → 重试到 max-attempts 后抛出，`verify` 上游被调用正好 3 次
- non-retryable 异常 → 上游只被调用 1 次
- 退避时长落在 `[0, cap]` 区间内（多次采样断言分布，不断言具体值）
- deadline 已过 → 不再重试
- 连续失败达阈值 → 状态变 OPEN，后续调用抛 `CircuitOpenException` 且上游调用次数不再增加
- OPEN 经过 `open-duration` 后 → 进入 HALF_OPEN，放行的探测数等于配置值
- HALF_OPEN 探测全部成功 → CLOSED；任一失败 → 回到 OPEN
- 熔断打开时 `/api/agent/chat` 返回 503 + `Retry-After` 头
- **降级响应未被写入缓存**（用 mock 断言 `cacheService.set` 未被以正常 TTL 调用）

> ⏱️ 时间控制：熔断器的状态机测试建议用可注入的 `Clock`（`java.time.Clock`）而不是 `Thread.sleep`，否则测试会很慢且不稳定。构造函数接受 `Clock`，测试里传 `Clock.fixed(...)` 并手动推进。

### ✅ Task 5 验收标准

- [ ] `./mvnw -B verify` 通过
- [ ] 全部超时值来自配置，无硬编码
- [ ] `OkHttpClient` 为单例 `@Bean`
- [ ] 退避实现为 full jitter，代码含注释说明原因
- [ ] 熔断器在 OPEN 状态下零上游调用
- [ ] 降级走 503 + `Retry-After`，且不污染缓存
- [ ] `grep -rn "resilience4j" pom.xml` 无结果（确认是手写的）

---

## Task 6 — 【P1】幂等写入（Idempotency-Key）

**目标**：这是本次改造中**与支付岗最直接对口**的一项。作用对象是写接口，不是 chat。

### 6.1 作用范围

- `POST /api/knowledge`（新增知识条目）
- `PUT /api/knowledge/{id}`、`DELETE /api/knowledge/{id}`（软删除）
- `POST /api/conversations`、`POST /api/conversations/{id}/messages`

`GET` 请求天然幂等，不处理。

### 6.2 实现方式

用注解 + AOP，**不要**用 `HandlerInterceptor`（拿不到返回对象，还要包 response wrapper，很脏）。

新建 `@Idempotent` 注解，标在 controller 方法上。新建 `IdempotencyAspect`（`@Around`），拿到 `ResponseEntity` 返回值直接序列化存储。

### 6.3 存储结构

Redis key：`idem:{httpMethod}:{routePattern}:{idempotencyKey}`

> 必须把 method 和 route 纳入 key。否则同一个 `Idempotency-Key` 打到不同接口会互相串,这是个真实的坑。

value（JSON）：

```json
{
  "state": "IN_PROGRESS | COMPLETED",
  "requestHash": "sha256(规范化后的请求体)",
  "httpStatus": 201,
  "responseBody": "...",
  "createdAt": "2026-08-17T10:00:00Z"
}
```

TTL：24 小时（可配置 `agent.idempotency.ttl-hours`）。

`requestHash` 的计算：对请求体做 JSON 规范化（key 排序、去空白）后取 sha256。**不要**直接对原始字节做 hash——字段顺序变化会导致误判。

### 6.4 完整流程（严格按此实现）

```
1. 读取 Idempotency-Key 请求头
   - 不存在 → 直接放行执行原方法（保持向后兼容，不要强制要求）
   - 存在但为空字符串或长度 > 255 → 400 Bad Request

2. 计算 requestHash

3. SET idem:... {state:IN_PROGRESS, requestHash, createdAt} NX EX 86400

4. SETNX 成功（我们拿到了执行权）：
   a. 执行原方法
   b. 正常返回（含 4xx 业务错误，如校验失败）
      → 覆写 key 为 {state:COMPLETED, requestHash, httpStatus, responseBody}，TTL 24h
      → 返回原响应
   c. 抛出未预期异常 / 5xx
      → DELETE key（让客户端重试有机会成功）
      → 异常继续向上抛
      ⚠️ 这里的区分是关键：4xx 是确定性结果，重放它是正确的；
         5xx 可能是瞬时故障,必须允许重试

5. SETNX 失败（key 已存在）→ GET 出来判断：
   a. requestHash 不匹配
      → 422 Unprocessable Entity
      → 响应体说明 "Idempotency-Key reused with a different request payload"
   b. state == COMPLETED
      → 回放存储的 httpStatus + responseBody
      → 加响应头 Idempotency-Replayed: true
   c. state == IN_PROGRESS
      → 409 Conflict + Retry-After: 1
      → 响应体说明 "A request with this Idempotency-Key is currently in progress"
      ⚠️ 不要在服务端轮询等待。轮询会占住 servlet 线程,
         高并发下直接打满线程池。让客户端重试是正确设计
```

### 6.5 Redis 不可用时的行为 ⭐

**必须 fail closed：返回 503,拒绝执行写操作。**

这与 chat 读路径的 fail open（Redis 挂了当 cache miss，继续服务）是**故意相反**的。在 README 的设计说明里明确写出这个对比：

> 读路径 Redis 故障时降级放行——最坏结果是多查一次数据库。
> 写路径 Redis 故障时降级拒绝——因为此时无法保证幂等,而重复写入的代价高于短暂不可用。

这个对比是整个项目最值得在面试里讲的一段话,务必在 README 里写清楚。

### 6.6 测试 `IdempotencyTest`

- 无 `Idempotency-Key` → 正常执行，行为与改造前一致
- 同 key 同 body 连续两次 → 第二次返回 `Idempotency-Replayed: true`，**数据库记录数为 1**
- 同 key 不同 body → 422
- **并发测试**：`CountDownLatch` 同步 2 个线程同时发同 key 同 body 请求 → 恰好一个执行成功，另一个得到 409 或回放；断言 `repository.count() == 1`
- 业务方法抛 500 → key 被删除；随后同 key 重试 → 能正常执行成功
- 业务方法返回 400 → key 保留为 COMPLETED；同 key 重试 → 回放 400
- Redis 抛异常 → 503（fail closed）

> 测试用 embedded Redis 还是 mock：优先 mock `RedisCacheService`/`StringRedisTemplate`，保持 Task 2 定下的"测试零外部依赖"约束。并发测试用 `ConcurrentHashMap` 实现一个假的支持 `setIfAbsent` 语义的 fake，比 mock 更好写。

### ✅ Task 6 验收标准

- [ ] `./mvnw -B verify` 通过
- [ ] 并发测试稳定通过（连跑 5 次不 flaky）
- [ ] 5xx 删 key、4xx 存 key 的差异化行为有测试覆盖
- [ ] Redis 故障时写接口 fail closed，读接口 fail open，各有测试
- [ ] README 有"读写降级方向相反"的设计说明段落
- [ ] Swagger/OpenAPI 文档中 `Idempotency-Key` 头有描述

> 🔖 **CHECKPOINT B** — 到这里可以在简历里写幂等和韧性工程。这是对支付岗差异化最大的一段。

---

## Task 7 — 【P1】可观测性

### 7.1 请求 ID 链路追踪

新建 `web/RequestIdFilter`，继承 `OncePerRequestFilter`，`@Order(Ordered.HIGHEST_PRECEDENCE)`：

- 读 `X-Request-Id` 请求头；不存在则生成 UUID（去掉横线，取前 16 位即可）
- 放入 MDC：`MDC.put("requestId", id)`
- 写入响应头 `X-Request-Id`
- **必须在 `finally` 里 `MDC.remove("requestId")`**——线程池复用会导致 MDC 泄漏到下一个请求，这是经典 bug

logback 配置（`logback-spring.xml`）的 pattern 里加入 `[%X{requestId}]`。

暂不引入 JSON 日志编码器（`logstash-logback-encoder`）——今天的时间预算下增加风险，收益不大。README 的 Roadmap 里提一句即可。

### 7.2 Micrometer 指标

加 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（**坐标先验证，见第 0 节**）。

新建 `observability/AgentMetrics.java`（`@Component`，构造注入 `MeterRegistry`），注册以下指标：

| 指标名 | 类型 | tags | 埋点位置 |
|---|---|---|---|
| `agent.cache.lookup` | Counter | `result=hit\|miss` | ChatService 缓存查询后 |
| `agent.refusal` | Counter | `stage=no_hits` | 拒答闸门触发时 |
| `agent.retrieval.hits` | DistributionSummary | — | 检索返回后，记录命中条数 |
| `agent.llm.call` | Timer | `outcome=success\|error\|timeout`, `attempt=1\|2\|3` | RetryExecutor 每次尝试 |
| `agent.circuit.state` | Gauge | `dependency=dashscope` | 0=CLOSED, 1=HALF_OPEN, 2=OPEN |
| `agent.idempotency` | Counter | `result=new\|replayed\|conflict\|mismatch\|unavailable` | IdempotencyAspect |
| `agent.degraded` | Counter | `reason=circuit_open\|retry_exhausted` | 降级返回时 |

⚠️ **tag 基数控制**：绝对不要把 `question` 内容、`Idempotency-Key`、用户输入的任何部分作为 tag 值。高基数 tag 会打爆时序数据库,这是生产事故级别的错误。代码里加注释。

### 7.3 Actuator 暴露与健康检查

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus     # 白名单，不要用 "*"
  endpoint:
    health:
      show-details: when-authorized
```

自定义 `RedisHealthIndicator`：

- Redis 可用 → `UP`
- Redis 不可用 → 返回 `UP` 但 details 里标注 `redis: DEGRADED`,**不要让整体 health 变成 DOWN**
- 设计理由（写进 README）：Redis 对读路径是可选依赖，服务此时仍能正确响应。把整体 health 标 DOWN 会导致 K8s/LB 把一个仍然健康的实例摘掉,反而放大故障

数据库不可用 → 整体 `DOWN`（这是必需依赖）。

### 7.4 测试

- `RequestIdFilterTest`：无请求头时响应头有 ID；带请求头时原样回传；断言 MDC 在请求结束后被清空
- `AgentMetricsTest`：用 `SimpleMeterRegistry`，跑一次拒答链路后断言 `agent.refusal` 计数为 1、`agent.llm.call` 计数为 0
- `/actuator/health` 返回 200；`/actuator/prometheus` 返回含 `agent_` 前缀指标的文本

### ✅ Task 7 验收标准

- [ ] `./mvnw -B verify` 通过
- [ ] `curl /actuator/prometheus | grep agent_` 有输出
- [ ] 日志行含 requestId,且响应头回传
- [ ] 无任何高基数 tag（人工 review 一遍所有 `Tags.of(...)` 调用）
- [ ] Actuator 走白名单暴露
- [ ] README 有"为什么 Redis 故障不把 health 标 DOWN"的说明

---

## Task 8 — 收尾（15 分钟，别跳过）

- [ ] README 更新：架构图（文字版即可）、Benchmarks 一节、三段设计说明（读写降级方向相反 / 503 vs 200 的区分 / health 指示器的取舍）、Roadmap（把向量检索、SSE 流式、鉴权限流、JSON 日志列为 future work——**主动写出已知局限比藏着好**）
- [ ] `.env.example` 列出所有需要的环境变量
- [ ] docker-compose 确认 `docker compose up` 后服务可用
- [ ] 全量跑一遍 `./mvnw -B clean verify`
- [ ] 合并到 `main`，确认 CI 绿
- [ ] 确认 GitHub Pages 项目主页内容与新 README 一致（简历链接指向它）

---

## 明确不做的事（今天）

以下都是"看起来更 AI"但对目标岗位收益低的方向，**不要动**：

- ❌ 向量/语义检索（embedding、pgvector、Milvus）
- ❌ SSE / WebSocket 流式输出
- ❌ 多轮会话上下文接入 chat 链路
- ❌ 鉴权（JWT/OAuth）与限流
- ❌ 缓存版本号命名空间（KB 更新时的缓存失效）
- ❌ 前端界面

全部写进 README 的 Roadmap。

---

## 任务依赖与时间预算

```
Task 1 (重构) ─┬─> Task 2 (测试) ──> Task 3 (CI)
               │                        │
               ├─> Task 4 (压测) <──────┘
               │
               ├─> Task 5 (韧性) ──┐
               ├─> Task 6 (幂等) ──┼──> Task 7 (可观测) ──> Task 8 (收尾)
               └───────────────────┘
```

| Task | 预算 | 累计 |
|---|---|---|
| 1 重构 | 60 min | 1h00 |
| 2 测试 | 90 min | 2h30 |
| 3 CI | 20 min | 2h50 |
| 4 压测 | 45 min | 3h35 |
| — **CHECKPOINT A** — | | |
| 5 韧性 | 90 min | 5h05 |
| 6 幂等 | 90 min | 6h35 |
| — **CHECKPOINT B** — | | |
| 7 可观测 | 60 min | 7h35 |
| 8 收尾 | 15 min | 7h50 |

若进度落后，**牺牲顺序**：Task 7 → Task 5 → Task 6。Task 1–4 不可牺牲（Task 4 直接产出简历数字）。
