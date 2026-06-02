# TPS Generator System Overhaul — Design & Roadmap

**Date:** 2026-06-02
**Scope:** Both repos — `TPSGenerator-Server` (mock target) and `TPSGenerator` (load client). Maximal scope: every correctness fix, optimization, and feature (including the large bets). Work committed directly to `master` in each repo. No push unless requested.

This is multi-session work. It is sequenced into phases so each phase is independently shippable and verifiable (build + tests green). Highest-impact / lowest-risk first.

## Ground rules

- Keep the build green after every phase (`mvn clean test` in each repo, JDK 21).
- Match existing code style/patterns; prefer small, focused files.
- Add/adjust tests for every behavioral change (TDD where practical).
- Preserve backward compatibility of config files where feasible; document breaks.

## Baseline (2026-06-02)

Both repos build and test green on JDK 21 (`/opt/homebrew/opt/openjdk@21`). Server: Spring Boot 3.2.5. Client: plain `java -jar` CLI (Spring Boot framing is vestigial; no Spring on classpath).

---

## Phase 1 — Server foundational fixes (`TPSGenerator-Server`)

1. **Enable virtual threads** — add `spring.threads.virtual.enabled=true`. Sleep-bound mock; ~10–100× concurrency. Verify per-endpoint `ReentrantLock` doesn't pin (it won't).
2. **Fix `/admin/**` auth bypass** — `SecurityConfig` only protects `/api/v1/admin/**`; the `/admin/**` alias is `permitAll()`. Add `.requestMatchers("/admin/**").authenticated()`. Add negative test proving `/admin/config` requires auth.
3. **Cut hot-path INFO logging** — `MockRequestController` logs 2 INFO lines + URI string-replace per request. Move to DEBUG, guard with `isDebugEnabled()`.
4. **Fix README auth section** — README says "no authentication"; Basic Auth is wired and `ADMIN_PASSWORD` required at startup. Rewrite to document real model + the alias behavior.
5. **Fix Micrometer tag inconsistency** — `mock_server_endpoint_requests_total` registered with `{endpoint}` and `{endpoint,result}`. Collapse to one consistent schema (always include `result`). Add test.
6. **Bound metric/lock cardinality** — caller-controlled path used as tag + lock key. Only tag configured endpoints (else `other`/templated), and LRU-cap the history/lock endpoint dimension.
7. **Consolidate persistence** — `MockEndpointService` inline auto-save and `ConfigurationPersistenceService` write different files with no coordination. Single mechanism; document it.
8. **Hot-path allocation** — config flag `mock-server.history.enabled` (default off); cache resolved meters per endpoint; reuse one immutable default config; short-circuit `errorRate<=0`.
9. **Header sanitization** — validate/limit configured `responseHeaders` (block framework-managed headers).
10. **`Thread.sleep` interrupt** — on interrupt, return promptly and report actual processing time.

## Phase 2 — Client correctness fixes (`TPSGenerator`)

1. **Coordinated omission** — capture scheduled send time before `rateLimiter.acquire()`; record latency from that origin; use `recordValueWithExpectedInterval(latency, 1e9/targetTps)`.
2. **Destructive `getLatencyPercentiles()`** — `getIntervalHistogram()` resets; accumulate into a total histogram like `ResponseTimeMetrics`.
3. **Timeouts** — set per-request `HttpRequest.timeout()` from config; cancel the exchange on timeout (don't just `orTimeout`); add a max-in-flight semaphore; stop recording timeout/error response times into the success percentile histogram.
4. **Unify latency pipelines** — one source of truth; consistent warm-up handling; report a single P95.
5. **Circuit breaker** — half-open/auto-reset; fix strict-`>` threshold; evaluate before window fills; remove per-request write-lock + O(window) recompute (running counters / ring buffer).
6. **Concurrency hygiene** — shared `java.util.Random` → `ThreadLocalRandom`; guard zero total weight in `selectTemplate`; ensure scheduler observes `startTime` (happens-before); fix `samples.remove(0)` O(n) → `ArrayDeque`/ring buffer (`TpsMetrics`, `ResourceMonitor`).
7. **TPS semantics** — distinguish offered vs achieved TPS; reconcile average vs per-second counters.
8. **Lifecycle** — single owner for `testRunning`; make `stop()`/`close()`/`complete` race-free; add `ExecutionControllerTest`.
9. **Charset** — `getBytes(UTF_8)` (or byte-array body) everywhere size is measured.
10. **logback package** — `com.example.tpsgenerator` → `io.kunkun.tpsgenerator`.
11. **Config validation drift** — `threadPool` is required-but-ignored under virtual threads; make optional, document.

## Phase 3 — Client throughput optimizations (`TPSGenerator`)

1. Shard submission loop / token-bucket batch acquire (removes single-thread pacing ceiling).
2. `BodyHandlers.ofByteArray()`/`discarding()` instead of `ofString()` when body unused.
3. Pre-compile templates: detect static templates, skip regex/`URI.create` per request; `StringBuilder` not `StringBuffer`.
4. Reduce metrics fan-out: pass start time through instead of `RequestTracker` map put/remove per request.
5. HTTP client executor + connection-pool sizing knobs.

## Phase 4 — Resolve phantom/dead code (`TPSGenerator`)

- **Wire `ResponseValidator`** into the engine behind a config flag (`responseValidation`).
- **InfluxDB** — wire a minimal exporter or remove dep+config (decision: remove unless trivially wired; superseded by Phase 6 dashboard/Prometheus).
- **NetworkMetrics bytes-sent** — wire request-size accounting or remove the dead path.
- Remove unused constants (`DEFAULT_REQUEST_TIMEOUT_SECONDS` once timeouts use it, `EXECUTOR_LOOP_SLEEP_MS`).

## Phase 5 — Bounded features

**Server:** weighted status-code distributions; latency distributions (uniform/normal/lognormal); stateful scenario sequencing (first-N then change; ramp latency); network-level fault injection (reset, slow-drip, malformed); response-body templating + size control.

**Client:** SLA assertions → process exit code (p95/p99/minTps/per-status budgets); run comparison mode (`compare A.json B.json`) with regression thresholds; chained/correlated requests (extract from response → next request) + think-time + multi-step sessions.

## Phase 6 — Large features

- **Dashboard link:** implement backend endpoints on the server (`/api/tests/register`, `/api/metrics/update`, `/api/tests/finish`, `/api/tests/result`, `X-API-Key`) and wire the client `DashboardClient`.
- **Live dashboard UI** (server-served, reads the collected data).
- **Server request-matching rules engine** (method/query/header/body JSONPath → response).
- **OpenAPI/Swagger import** → generate endpoints + example responses.
- **Record/replay proxy mode** (proxy to upstream, capture, replay).
- **Client Prometheus/Grafana export** and/or live TUI.
- **Distributed multi-node load generation** (coordinator shards target TPS; merge HDR histograms).
- **gRPC / WebSocket** support (server endpoints + client drivers).

---

## Execution order & status

Phases run in order 1→6; within a phase, items are independent and grouped into focused commits. Progress tracked in the session task list (Phase 1–6 tasks). Each phase ends with a green `mvn clean test` in the affected repo(s) before moving on.
