# Benchmarks

All numbers below were measured on the machine and configuration described in this
file. **Nothing here is estimated or extrapolated.** Re-run them yourself with the
`Repro` command printed under each result.

## Method

`benchmarks/bench.py` drives `GET /api/agent/chat` and reports nearest-rank
percentiles over wall-clock latency, so every quoted percentile is an observed
request, not an interpolated value. Warmup requests are excluded from all statistics.

Three scenarios, one per code path through `ChatService`:

| scenario | what it exercises | how the input is built |
|---|---|---|
| `refusal` | fail-fast path: retrieval returns 0 hits, refusal gate fires, **no LLM call** | a random UUID, which cannot be a substring of any `question` or `keywords` |
| `cache_hit` | Redis hit, short-circuits before retrieval and before the LLM | one fixed question, warmed into the cache first |
| `llm_path` | full chain: retrieval → prompt assembly → upstream model → cache write-back | fixed retrievable question plus a punctuation-only suffix (see below) |

### Why `llm_path` uses a punctuation suffix

Retrieval is `question LIKE %q%` OR `keywords LIKE %q%`, so `q` has to be a
*substring* of the stored text. Appending a UUID to make the cache key unique
therefore makes retrieval **miss**, silently turning `llm_path` into a second
`refusal` benchmark — the exact trap this design avoids.

The suffix is built from characters that `QuestionNormalizer` strips. The first
`LIKE` misses, the normalized retry drops the suffix and hits, and the cache key —
`sha256` of the trimmed question — stays unique. The cost is that `llm_path` pays
two `SELECT`s instead of one; that is the retrieval path's worst case, and it is
noise next to a ~2.4 s upstream call.

The script asserts this rather than trusting it: it records the `hits` value of
every response and warns if `refusal` ever sees `hits > 0`, or if `cache_hit` /
`llm_path` ever see `hits == 0`. The runs below observed `hits=[0]` for `refusal`
and `hits=[1]` for the other two.

---

## Environment

| | |
|---|---|
| **Measured** | 2026-08-17 |
| **CPU** | AMD Ryzen 7 8745H (8 physical / 16 logical cores) |
| **RAM** | 15.3 GB |
| **OS** | Windows 11 Home China, 10.0.26200 |
| **JVM** | OpenJDK 17.0.12+8-LTS-286 (Java 17) |
| **Spring Boot** | 4.0.1 |
| **Profile** | `prod` |
| **MySQL** | 8.0.44 (docker-compose, `mysql:8.0`) |
| **Redis** | 7.4.7 (docker-compose, `redis:7-alpine`) |
| **Load generator** | Python 3.13.15, same host as the service (so latencies exclude real network RTT to the service, but include real RTT to DashScope) |

Key configuration in effect:

| property | value |
|---|---|
| `agent.cache.enabled` | `true` |
| `agent.cache.ttl-seconds` | `600` |
| `agent.cache.refusal-ttl-seconds` | `30` |
| `spring.jpa.open-in-view` | `false` (see *Finding* below) |
| Hikari `maximum-pool-size` | `10` (Spring Boot default, left untouched) |
| Tomcat `max-threads` | `200` (Spring Boot default, left untouched) |
| `agent.llm.model` | `qwen-plus` |
| `agent.llm.temperature` | `0.3` |

> The load generator shares the host with the service, MySQL and Redis. On a
> 16-core box at these concurrency levels that is not the bottleneck, but it does
> mean the absolute throughput figures are optimistic relative to a real
> deployment with network hops between tiers.

---

## Summary

| scenario | n | concurrency | p50 | p95 | p99 | throughput | upstream |
|---|---|---|---|---|---|---|---|
| `refusal` | 500 | 20 | 23.4 ms | 33.3 ms | 43.0 ms | 778 req/s | not called |
| `cache_hit` | 500 | 20 | 13.5 ms | 21.9 ms | 26.3 ms | 1321 req/s | not called |
| `llm_path` (latency) | 20 | 1 | 2364 ms | 3175 ms | 3175 ms | — | **real DashScope** |
| `llm_path` (throughput) | 500 | 20 | 2401 ms | 2446 ms | 2469 ms | 8.3 req/s | stub, 2364 ms injected |

**The refusal gate is worth roughly two orders of magnitude**: p95 33.3 ms versus
3175 ms, i.e. **95×** — and it costs zero API quota. Every question the knowledge
base cannot ground is answered without touching the model.

A cache hit is a further ~1.5× cheaper than a refusal (21.9 ms vs 33.3 ms at p95),
because it returns before touching the database at all.

The two `llm_path` rows measure different things and must not be conflated:

- **latency** (`n=20, sequential, real DashScope`) is what a user actually waits.
  Small sample, and it consumes real API quota.
- **throughput** (`stubbed upstream, 2364 ms injected delay`) is what the service
  itself can carry, independent of how the upstream feels today. The injected delay
  is the p50 measured in the row above it, so the two rows are directly comparable.

The throughput row is the more informative of the two. Its p50 of 2401 ms sits
**37 ms above the 2364 ms injected delay** — that 1.6 % is everything the service
itself contributes: two `SELECT`s, prompt assembly, a Redis lookup and a Redis
write. And 8.3 req/s is within 2 % of the theoretical `20 / 2.364 s = 8.46 req/s`,
which says the service adds no queueing of its own at concurrency 20. Under this
workload the service is not the bottleneck; the upstream model is. That is the
number worth defending in an interview, and it only became true after the
`open-in-view` fix below.

---

## Finding: `spring.jpa.open-in-view` capped concurrency at the Hikari pool size

This benchmark was not decoration — it surfaced a real production bug.

The first `llm_path` throughput run, at `--concurrency 20` with a 2814 ms injected
delay, reported **p50 5660 ms and 3.5 req/s**. Both are almost exactly *half* of
what the configuration should give (`20 / 2.814 s ≈ 7.1 req/s`), and a p50 of twice
the injected delay means every request waited through one other request first. So
only ~10 requests were ever in flight — and 10 is the Hikari default pool size.

Cause: `spring.jpa.open-in-view` defaults to **on**. The `EntityManager`, and with
it a JDBC connection, is held for the whole request — including the ~2.8 s upstream
model call, which needs no database at all. The connection pool, not the 200-thread
Tomcat executor, became the concurrency ceiling.

Confirmed by A/B, 100 requests at concurrency 20 each:

| configuration | p50 | throughput |
|---|---|---|
| pool 10, `open-in-view=true` (original) | 5660 ms | 3.5 req/s |
| pool **20**, `open-in-view=true` | 2866 ms | 6.9 req/s |
| pool 10, `open-in-view=**false**` | 2874 ms | 6.9 req/s |

Both changes recover the throughput, which isolates the cause to connection-hold
duration. The fix is the second one: **release the connection before the slow call**,
not inflate the pool. Growing the pool only moves the ceiling — the connections are
still idle-but-held for 2.8 s, and MySQL pays for every one of them. Every interface
in this service returns DTOs and never hands a lazy entity to a view layer, so
`open-in-view` bought nothing in the first place.

`spring.jpa.open-in-view=false` is now set in `application.properties`.

> Spring Boot logs a warning about this default on every startup. It is easy to
> scroll past; a load test with a slow downstream call makes it impossible to miss.

---

## Reproducibility

Every scenario was run twice back to back with identical parameters. Run A is the
one recorded under *Raw runs*; run B exists only to check the numbers hold. The bar
is < 30 % difference in p95.

| scenario | run A p95 | run B p95 | delta |
|---|---|---|---|
| `refusal` | 33.3 ms | 31.2 ms | 6.3 % |
| `cache_hit` | 21.9 ms | 23.8 ms | 8.7 % |
| `llm_path` (real, n=20, sequential) | 3174.9 ms | 2927.7 ms | 7.8 % |
| `llm_path` (stub, 2364 ms injected) | 2446.0 ms | 2436.7 ms | 0.4 % |

Two things had to be got right before these held:

**`refusal` needs a bigger warmup than the 10 % default.** At the default 50 warmup
requests, two consecutive runs gave p95 56.4 ms and 33.0 ms — a 41 % spread, over the
bar. The path is cheap enough (~23 ms) that the JVM is still interpreting it after 50
requests. 2000 warmup requests, about two seconds of traffic, puts it in steady
state. The recorded run therefore passes `--warmup 2000` explicitly, and the repro
command records it.

**The host has to be quiet.** An earlier pair measured 28.6 ms and 37.8 ms — a 32 %
spread — while Docker Desktop was still doing post-start background work. At a p95
of ~30 ms, 9 ms of absolute noise is a third of the value, so this scenario is the
one that will look flaky on a busy laptop. Six consecutive runs on a settled host
landed in a 24–30 ms band. Nothing about that is a property of the service; it is a
property of measuring a 23 ms operation with the load generator, MySQL and Redis all
sharing one machine. The `llm_path` rows, whose scale is a thousand times larger,
reproduce to within 8 % regardless.

Because of that, treat the sub-millisecond digits in this document as noise. The
claims worth quoting are the ratios, which are stable: refusal is ~2 orders of
magnitude cheaper than the full chain, and the service adds ~1.6 % on top of
upstream latency.

---

## Raw runs

### `refusal`

- **Measured at**: 2026-08-17T22:14:59+08:00
- **Host**: Windows-11-10.0.26200-SP0 / AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD / 16 logical cores
- **Python**: 3.13.15
- **Target**: `http://localhost:8080/api/agent/chat`
- **Scenario**: `refusal`
- **Requests**: 500 measured, 2000 warmup (warmup excluded from all statistics)
- **Concurrency**: 20
- **Client timeout**: 60.0s
- **Repro**: `python benchmarks/bench.py --scenario refusal --concurrency 20 --requests 500 --warmup 2000 --url http://localhost:8080/api/agent/chat`
- **hits values observed**: [0]

| scenario | n | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | mean (ms) | max (ms) | throughput (req/s) | errors |
|---|---|---|---|---|---|---|---|---|---|
| `refusal` | 500 | 23.4 | 30.7 | 33.3 | 43.0 | 24.5 | 50.4 | 777.5 | 0 |

### `cache_hit`

- **Measured at**: 2026-08-17T22:15:37+08:00
- **Host**: Windows-11-10.0.26200-SP0 / AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD / 16 logical cores
- **Python**: 3.13.15
- **Target**: `http://localhost:8080/api/agent/chat`
- **Scenario**: `cache_hit`
- **Requests**: 500 measured, 2000 warmup (warmup excluded from all statistics)
- **Concurrency**: 20
- **Client timeout**: 60.0s
- **Repro**: `python benchmarks/bench.py --scenario cache_hit --concurrency 20 --requests 500 --warmup 2000 --url http://localhost:8080/api/agent/chat`
- **hits values observed**: [1]

| scenario | n | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | mean (ms) | max (ms) | throughput (req/s) | errors |
|---|---|---|---|---|---|---|---|---|---|
| `cache_hit` | 500 | 13.5 | 19.8 | 21.9 | 26.3 | 14.1 | 31.8 | 1320.6 | 0 |

### `llm_path` — real DashScope, qwen-plus, sequential

- **Measured at**: 2026-08-17T22:17:15+08:00
- **Host**: Windows-11-10.0.26200-SP0 / AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD / 16 logical cores
- **Python**: 3.13.15
- **Target**: `http://localhost:8080/api/agent/chat`
- **Scenario**: `llm_path`
- **Requests**: 20 measured, 2 warmup (warmup excluded from all statistics)
- **Concurrency**: 1
- **Client timeout**: 60.0s
- **Repro**: `python benchmarks/bench.py --scenario llm_path --concurrency 1 --requests 20 --warmup 2 --url http://localhost:8080/api/agent/chat`
- **hits values observed**: [1]

| scenario | n | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | mean (ms) | max (ms) | throughput (req/s) | errors |
|---|---|---|---|---|---|---|---|---|---|
| `llm_path` | 20 | 2363.7 | 2984.2 | 3174.9 | 3174.9 | 2513.4 | 3174.9 | 0.4 | 0 |

### `llm_path` — stubbed upstream, 2364ms injected delay (= measured real p50)

- **Measured at**: 2026-08-17T22:20:08+08:00
- **Host**: Windows-11-10.0.26200-SP0 / AMD64 Family 25 Model 117 Stepping 2, AuthenticAMD / 16 logical cores
- **Python**: 3.13.15
- **Target**: `http://localhost:8080/api/agent/chat`
- **Scenario**: `llm_path`
- **Requests**: 500 measured, 100 warmup (warmup excluded from all statistics)
- **Concurrency**: 20
- **Client timeout**: 60.0s
- **Repro**: `python benchmarks/bench.py --scenario llm_path --concurrency 20 --requests 500 --warmup 100 --url http://localhost:8080/api/agent/chat`
- **hits values observed**: [1]

| scenario | n | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | mean (ms) | max (ms) | throughput (req/s) | errors |
|---|---|---|---|---|---|---|---|---|---|
| `llm_path` | 500 | 2401.4 | 2434.2 | 2446.0 | 2468.9 | 2405.2 | 2485.2 | 8.3 | 0 |
