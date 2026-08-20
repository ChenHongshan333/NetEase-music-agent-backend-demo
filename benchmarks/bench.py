#!/usr/bin/env python3
"""Latency / throughput benchmark for the NetEase support agent chat endpoint.

Standard library only -- no external packages, so it runs anywhere Python 3.9+ does.

Three scenarios, matching the three code paths through ChatService:

  refusal    retrieval is guaranteed to return 0 hits, so the refusal gate
             short-circuits before any LLM call. Measures the fail-fast path.
  cache_hit  one fixed question, warmed up so every measured request is a
             Redis hit. Measures the cache path.
  llm_path   a unique suffix per request forces a cache miss while keeping
             retrieval hits, so every measured request goes all the way to
             the upstream model. Measures the full chain.

Example:
  python benchmarks/bench.py --scenario refusal --concurrency 20 --requests 500
  python benchmarks/bench.py --scenario llm_path --concurrency 1  --requests 20
"""

from __future__ import annotations

import argparse
import json
import os
import platform
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from datetime import datetime, timezone

DEFAULT_URL = "http://localhost:8080/api/agent/chat"

# Must actually exist in data.sql, otherwise cache_hit/llm_path silently
# degrade into another refusal benchmark and the numbers become meaningless.
HIT_QUESTION = "黑胶VIP会员价格是多少？"

# Alphabet used to build unique cache-busting suffixes for llm_path.
#
# Retrieval is `question LIKE %q%` OR `keywords LIKE %q%`, so q has to be a
# SUBSTRING of the stored text -- appending arbitrary characters (a UUID, say)
# makes retrieval miss and quietly turns llm_path into a second refusal
# benchmark. The cache key, meanwhile, is sha256 of the trimmed question, so it
# is sensitive to every character.
#
# The way out is characters that ChatService's fallback path strips: these are
# all matched by \p{Punct} in QuestionNormalizer, so the first LIKE misses, the
# normalized retry drops the suffix, and retrieval hits -- while the cache key
# stays unique. Cost: llm_path pays two SELECTs instead of one. That is the
# retrieval path's worst case, and it is dwarfed by upstream latency either way.
# % _ ' \ are deliberately left out to keep the logs readable.
SUFFIX_ALPHABET = "!#$&()*+,-./:;<=>?@[]^{|}~"


@dataclass
class Sample:
    latency_ms: float
    status: int
    hits: int | None


@dataclass
class Outcome:
    samples: list[Sample] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    lock: threading.Lock = field(default_factory=threading.Lock)

    def record(self, sample: Sample) -> None:
        with self.lock:
            self.samples.append(sample)

    def fail(self, message: str) -> None:
        with self.lock:
            self.errors.append(message)


def punctuation_suffix(n: int) -> str:
    """Encode n in base-len(SUFFIX_ALPHABET) using punctuation only."""
    base = len(SUFFIX_ALPHABET)
    out = SUFFIX_ALPHABET[n % base]
    n //= base
    while n > 0:
        out += SUFFIX_ALPHABET[n % base]
        n //= base
    return out


def build_question(scenario: str, index: int, run_salt: int = 0) -> str:
    if scenario == "refusal":
        # A random UUID cannot appear in question or keywords, so retrieval
        # returns 0 hits and the refusal gate fires.
        return f"zz{uuid.uuid4().hex}"
    if scenario == "cache_hit":
        return HIT_QUESTION
    if scenario == "llm_path":
        # Unique cache key, retrieval still hits. See SUFFIX_ALPHABET.
        return HIT_QUESTION + punctuation_suffix(run_salt + index)
    raise ValueError(f"unknown scenario: {scenario}")


def fire(url: str, question: str, timeout: float) -> Sample:
    full = f"{url}?{urllib.parse.urlencode({'question': question})}"
    request = urllib.request.Request(full, headers={"Accept": "application/json"})

    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read()
            elapsed_ms = (time.perf_counter() - started) * 1000.0
            hits = None
            try:
                hits = json.loads(body).get("hits")
            except (ValueError, AttributeError):
                pass
            return Sample(elapsed_ms, response.status, hits)
    except urllib.error.HTTPError as e:
        elapsed_ms = (time.perf_counter() - started) * 1000.0
        return Sample(elapsed_ms, e.code, None)


def run_phase(url: str, scenario: str, count: int, concurrency: int,
              timeout: float, label: str, run_salt: int = 0) -> Outcome:
    outcome = Outcome()
    if count <= 0:
        return outcome

    def task(index: int) -> None:
        question = build_question(scenario, index, run_salt)
        try:
            outcome.record(fire(url, question, timeout))
        except Exception as e:  # noqa: BLE001 - any transport failure counts as an error
            outcome.fail(f"{type(e).__name__}: {e}")

    print(f"  {label}: {count} requests @ concurrency {concurrency} ...",
          file=sys.stderr, flush=True)
    with ThreadPoolExecutor(max_workers=concurrency) as pool:
        list(pool.map(task, range(count)))
    return outcome


def percentile(sorted_values: list[float], q: float) -> float:
    """Nearest-rank percentile. No interpolation, so the number is always an
    observed latency rather than a synthetic one."""
    if not sorted_values:
        return float("nan")
    rank = max(1, min(len(sorted_values), int(round(q / 100.0 * len(sorted_values) + 0.5))))
    return sorted_values[rank - 1]


def summarise(outcome: Outcome, wall_seconds: float) -> dict:
    latencies = sorted(s.latency_ms for s in outcome.samples)
    non_2xx = [s for s in outcome.samples if not 200 <= s.status < 300]
    n = len(latencies)
    return {
        "n": n,
        "p50": percentile(latencies, 50),
        "p90": percentile(latencies, 90),
        "p95": percentile(latencies, 95),
        "p99": percentile(latencies, 99),
        "mean": statistics.fmean(latencies) if latencies else float("nan"),
        "max": latencies[-1] if latencies else float("nan"),
        "throughput": n / wall_seconds if wall_seconds > 0 else float("nan"),
        "errors": len(outcome.errors) + len(non_2xx),
        "hits_seen": sorted({s.hits for s in outcome.samples if s.hits is not None}),
    }


def markdown_table(rows: list[tuple[str, dict]]) -> str:
    header = ("| scenario | n | p50 (ms) | p90 (ms) | p95 (ms) | p99 (ms) | "
              "mean (ms) | max (ms) | throughput (req/s) | errors |")
    sep = "|---|---|---|---|---|---|---|---|---|---|"
    lines = [header, sep]
    for name, s in rows:
        lines.append(
            f"| `{name}` | {s['n']} | {s['p50']:.1f} | {s['p90']:.1f} | "
            f"{s['p95']:.1f} | {s['p99']:.1f} | {s['mean']:.1f} | {s['max']:.1f} | "
            f"{s['throughput']:.1f} | {s['errors']} |"
        )
    return "\n".join(lines)


def environment_block(args, summary: dict) -> str:
    return "\n".join([
        f"- **Measured at**: {datetime.now(timezone.utc).astimezone().isoformat(timespec='seconds')}",
        f"- **Host**: {platform.platform()} / {platform.processor() or 'unknown CPU'} "
        f"/ {os.cpu_count()} logical cores",
        f"- **Python**: {platform.python_version()}",
        f"- **Target**: `{args.url}`",
        f"- **Scenario**: `{args.scenario}`",
        f"- **Requests**: {args.requests} measured, {summary['warmup']} warmup "
        f"(warmup excluded from all statistics)",
        f"- **Concurrency**: {args.concurrency}",
        f"- **Client timeout**: {args.timeout}s",
        f"- **Repro**: `{summary['command']}`",
    ])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--concurrency", type=int, default=1)
    parser.add_argument("--requests", type=int, default=100,
                        help="measured requests (warmup is extra)")
    parser.add_argument("--scenario", required=True,
                        choices=["refusal", "cache_hit", "llm_path"])
    parser.add_argument("--warmup", type=int, default=None,
                        help="warmup requests; default = 10%% of --requests, min 1")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--out", default="docs/benchmarks.md",
                        help="append the markdown result here; '-' to skip")
    parser.add_argument("--note", default="",
                        help="free-text note recorded with the run, e.g. 'real DashScope'")
    args = parser.parse_args()

    warmup = args.warmup if args.warmup is not None else max(1, args.requests // 10)

    command = (f"python benchmarks/bench.py --scenario {args.scenario} "
               f"--concurrency {args.concurrency} --requests {args.requests} "
               f"--warmup {warmup} --url {args.url}")

    print(f"scenario={args.scenario} requests={args.requests} "
          f"concurrency={args.concurrency} warmup={warmup}", file=sys.stderr)

    # Per-invocation salt so llm_path questions differ between runs. Without it a
    # second run inside the 600s cache TTL would replay the first run's cached
    # answers and report cache_hit latency under the llm_path label.
    run_salt = uuid.uuid4().int % 100_000_000

    # Warmup is not just JIT: for cache_hit it is what puts the entry in Redis,
    # and for every scenario it pays the Hibernate/connection-pool first-use cost.
    warm_concurrency = 1 if args.scenario == "cache_hit" else args.concurrency
    warm = run_phase(args.url, args.scenario, warmup, warm_concurrency,
                     args.timeout, "warmup", run_salt)
    if warm.errors:
        print(f"  warmup errors: {warm.errors[:3]}", file=sys.stderr)

    started = time.perf_counter()
    measured = run_phase(args.url, args.scenario, args.requests, args.concurrency,
                         args.timeout, "measure", run_salt + warmup)
    wall = time.perf_counter() - started

    stats = summarise(measured, wall)
    if stats["n"] == 0:
        print("no samples collected", file=sys.stderr)
        return 1

    stats_meta = {"warmup": warmup, "command": command}

    print()
    print(f"### `{args.scenario}`" + (f" — {args.note}" if args.note else ""))
    print()
    print(environment_block(args, stats_meta))
    print(f"- **hits values observed**: {stats['hits_seen']}")
    print()
    print(markdown_table([(args.scenario, stats)]))
    print()

    if args.out != "-":
        os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
        with open(args.out, "a", encoding="utf-8") as f:
            f.write(f"\n### `{args.scenario}`"
                    + (f" — {args.note}" if args.note else "") + "\n\n")
            f.write(environment_block(args, stats_meta) + "\n")
            f.write(f"- **hits values observed**: {stats['hits_seen']}\n\n")
            f.write(markdown_table([(args.scenario, stats)]) + "\n")
        print(f"appended to {args.out}", file=sys.stderr)

    # A benchmark whose requests were failing is not a benchmark.
    if stats["errors"] > 0:
        print(f"WARNING: {stats['errors']} non-2xx/failed requests", file=sys.stderr)

    # Guard against silently benchmarking the wrong path.
    if args.scenario == "refusal" and stats["hits_seen"] not in ([0], []):
        print(f"WARNING: refusal scenario saw hits={stats['hits_seen']}, expected [0]",
              file=sys.stderr)
    if args.scenario in ("cache_hit", "llm_path") and stats["hits_seen"] in ([0], []):
        print(f"WARNING: {args.scenario} saw hits=0 -- retrieval missed, so this "
              f"measured the refusal path instead", file=sys.stderr)

    return 0


if __name__ == "__main__":
    sys.exit(main())
