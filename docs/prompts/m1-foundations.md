# M1 Foundations kickoff

Read `CLAUDE.md`, then section 5 of `docs/zero-cost-build.md` and section 3 of `docs/frs.md` (TC.01, TC.03, TC.05, TC.10).

## Goal

A green multi module Gradle build that every later milestone builds on: SBE code generation, the core value types (fixed point prices, injected clock, instrument model), an enforced zero allocation check, a JMH and latency harness skeleton, and CI on Java 21 and 25.

Out of scope for M1: Aeron, venue-sim, feed-handler, Protobuf, anything Spring.

## Tasks

1. Repo hygiene: `.gitignore`, `.claude/rules/hot-path.md`, doc paths that match `CLAUDE.md`.
2. Gradle skeleton: `settings.gradle.kts`, `gradle/libs.versions.toml`, `buildSrc` convention plugins (`rates.java-conventions`, `rates.hotpath-conventions`), Gradle wrapper. Base package `io.github.sudhanadithya.rates`.
3. `schemas`: SBE schema `rates-md.xml` (id 1, version 0) with `SourceTag` (`REAL`, `SYNTHETIC`, `LIVE_PROXY`), `Side`, `UpdateAction`, and the messages `RecordingHeader`, `InstrumentDefinition`, `BookUpdate`, `Trade`. Prices as `int64` ticks, no floating point fields. Codecs generated at build time with `SbeTool`, never committed.
4. `common`: `Clock` (`long nanos()`), `SystemClock` (the only class allowed to read system time), `ManualClock` (virtual time for sim and tests); `TickSize` and `Prices` (ticks as `long`, tick size in 1/256 points, 32nds notation at the edges only); `Instrument` and `InstrumentRegistry` (array indexed by id).
5. Enforcement: `allocationTest` (tests tagged `allocation`, Epsilon GC, plus a thread allocated bytes assertion) and `checkBannedTimeCalls` (no `System.nanoTime`, `System.currentTimeMillis` or `Instant.now` outside `SystemClock` in the rule 4 modules).
6. `bench`: JMH benchmarks for the SBE codecs and price helpers; `:bench:run` latency harness that prints p50, p99, p99.9 and max with the machine spec.
7. CI: GitHub Actions matrix on Temurin 21 and 25 running `./gradlew build allocationTest`.

## Done when

- `./gradlew build` and `./gradlew allocationTest` are green locally and in CI.
- A deliberate allocation in a tagged test fails `allocationTest`, and a deliberate `System.nanoTime()` call fails `checkBannedTimeCalls`.
- `./gradlew :bench:run` prints percentiles with the machine spec next to them.
