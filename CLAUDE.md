# Rates Trading Platform

An event driven, low latency systematic trading platform for US rates (CME Treasury futures first), written in Java. It is a portfolio project that mirrors a bank rates trading stack at zero cost: a simulated CME style exchange (`venue-sim`) stands in for the real venue.

**Why it exists:** to give me (Sudhan) real hands on experience with the work of a Quant Developer on a rates systematic trading desk: low latency event driven Java, execution algorithms, TCA, liquidity modeling, multithreaded replay backtesting, Aeron and ZeroMQ, SBE and Protobuf. Prefer code I can explain line by line in an interview over clever code.

## Source of truth docs

Read the doc that matters for the task before planning. Don't load all of them unless needed.

- `docs/zero-cost-build.md`: what we are actually building (module design, free substitutes, data sources, milestones M1 to M6). **This wins when docs disagree.**
- `docs/frs.md`: requirement IDs (`FR.MD.01` and so on) with acceptance criteria. Phase 2 and 3 items are out of scope unless I ask.
- `docs/architecture.md`: the firm grade target design and the reasons behind each choice.
- `docs/prompts/`: the kickoff prompt for each milestone.

## Current milestone

**M1 Foundations.** Update this line when a milestone is done.

## Stack

Every version is pinned in `gradle/libs.versions.toml`. Don't add, remove or upgrade a dependency without asking me first.

- Java 21 (Temurin) toolchain; CI also runs on Java 25. Don't use language or API features newer than 21.
- Gradle with Kotlin DSL, multi module build, version catalog
- Hot path: plain Java wired by hand in `main` (no DI framework); Aeron (IPC, UDP, Archive, Cluster), Agrona, SBE, HdrHistogram, OpenHFT Java Thread Affinity
- FIX: QuickFIX/J for the simulated cash venue
- Off the hot path: JeroMQ with Protobuf; Redpanda or Kafka in Docker (from M6)
- Spring Boot only in the `*-service` modules and `ops-gateway` (see section 3a of `docs/zero-cost-build.md`). They talk to the core only through messages: commands go in on Aeron Cluster ingress, and they read the core's events (Aeron egress, ZeroMQ telemetry, Kafka).
- Infrastructure for the Spring side runs in one `docker-compose.yml`: Postgres, Redpanda, Keycloak, Prometheus, Grafana.
- Tests: JUnit 5, AssertJ, JMH
- Research: Python 3 in `research/` (polars, pyarrow, numpy, statsmodels, QuantLib)

## Modules

| Module | Purpose | Hot path |
| --- | --- | --- |
| `schemas` | SBE and Protobuf schemas, generated codecs | n/a |
| `common` | fixed point prices, clocks, instrument model, shared utilities | yes |
| `venue-sim` | matching engine, MDP style A/B feed, iLink style order sessions, chaos switches | yes |
| `feed-handler` | A/B arbitration, gap recovery, MBO books | yes |
| `pricing` | microprice, implied yields, DV01 | yes |
| `strategy-api`, `strategies` | callback API with injected clock; imbalance and curve strategies | yes |
| `execution` | passive, implementation shortfall, curve executor | yes |
| `risk` | pre trade checks, limits, kill switch | yes |
| `oms`, `gateway` | order state machine, positions, venue sessions, FIX | yes |
| `recorder`, `sim` | Aeron Archive tooling, deterministic replay and backtest runner | `sim` yes |
| `tca` | TCA metric library in Java; notebooks live in `research/` | no |
| `bench` | JMH benchmarks and the end to end latency harness | n/a |
| `event-bridge` | plain Java: core events out to Kafka | no |
| `refdata-service` | Spring Boot: contracts, calendars, conversion factors (Postgres) | no |
| `risk-admin-service` | Spring Boot: four eyes limit changes, sent to cluster ingress | no |
| `ops-gateway` | Spring Boot: REST and WebSocket backend for the console | no |
| `tca-service` | Spring Boot: consumes Kafka trade events, runs the `tca` library | no |

**Deployable processes.** The core runs as plain `java -jar` processes: `md-gateway` (feed-handler), `trading-engine` (pricing, strategies, execution, risk), `core-cluster` (oms state on Aeron Cluster), `venue-gateway` (gateway), `recorder`, `event-bridge`, plus `venue-sim` as the outside world. Each wires its objects by hand in `main`. The four Spring Boot services run in Docker next to the infrastructure.

Create modules only when the current milestone needs them.

## Commands

Keep this section accurate. Update it in the same commit whenever a command changes.

- Build and run all tests: `./gradlew build`
- One module: `./gradlew :feed-handler:test`
- Zero allocation check: `./gradlew allocationTest` (tests tagged `allocation`, run under Epsilon GC; also part of `build`)
- Banned system time calls: `./gradlew checkBannedTimeCalls` (hot path modules; also part of `build`)
- Benchmarks (local only, not in CI): `./gradlew :bench:jmh`
- Latency harness: `./gradlew :bench:run` (prints p50, p99, p99.9, max)
- Python tests: `python -m pytest research/`

## Architecture rules (all code)

1. Components talk only through SBE messages on Aeron streams. No mutable objects shared across threads.
2. Single writer: each piece of state is owned and mutated by exactly one thread.
3. Prices are fixed point `long` values in ticks of the instrument; quantities are `long`. Never use `double` for prices or money in core modules. Convert only at the edges (ingest and reports).
4. Time always comes from an injected clock interface. Never call `System.currentTimeMillis`, `System.nanoTime` or `Instant.now` directly in `strategies`, `execution`, `risk`, `oms`, `venue-sim` or `sim`.
5. Determinism: the same recording plus the same seed must give byte identical output. No unseeded `Random`, no wall clock, no logic that depends on hash map iteration order.
6. Schemas live in `schemas/`. Changing a schema means bumping its version and saying in the commit whether old recordings still decode.
7. Every recording carries a source tag: `REAL`, `SYNTHETIC` or `LIVE_PROXY`. Never present PnL from synthetic data as real.

Detailed hot path rules are in `.claude/rules/hot-path.md` and load automatically for hot path files.

## Testing

- Write or update the test first. Name tests after FRS IDs, for example `@DisplayName("FR.MD.02 gap triggers snapshot recovery")`.
- Every bug fix gets a regression test.
- A change to a hot path module must keep `./gradlew allocationTest` green, and needs a JMH benchmark when it touches a per message method.
- Never weaken, skip or delete a failing test to make the build pass. Stop and tell me instead.

## How we work

- **Plan first** for anything that touches more than one module or any schema. Show the plan and wait for my approval.
- One milestone task or one FRS group per session. Small commits with messages like `M2: FR.MD.01 A/B arbitration`.
- When you finish a task: run the build and tests, then summarize what changed, what is left, and in two or three lines why any low latency design choice was made.
- **Ask before** adding dependencies, changing schemas, deleting files, editing CI workflows, or anything that downloads data or could cost money. For Databento, always call the cost API first and show me the price.
- **Learning mode:** when I say "review mode", don't write code. Review mine for allocation, false sharing, branching, correctness and determinism problems, and ask me questions. When I say "explain", walk me through your code the way an interviewer would probe it.
- Secrets come from environment variables (for example `DATABENTO_API_KEY`). Never commit data files, keys or `.env`.

## Don'ts

- No Spring, Lombok, or reflection heavy frameworks in hot path modules.
- No `synchronized`, `BlockingQueue` or `ConcurrentHashMap` on the hot path.
- No mocking frameworks where a real SBE flyweight over a buffer works.

## Writing style for README and docs

Plain, specific sentences. Put the machine spec and settings next to every latency number. Avoid hyphenated compound words in prose.
