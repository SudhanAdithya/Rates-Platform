# M2 Exchange and market data kickoff

Read `CLAUDE.md`, section 5 of `docs/zero-cost-build.md` (venue-sim, feed-handler), section 4 of `docs/frs.md` (FR.MD) and the "Hot path engineering" and "Messaging" sections of `docs/architecture.md`.

## Goal

`venue-sim` runs a FIFO matching engine driven by seeded synthetic order flow and publishes an MDP style feed on A, B and snapshot channels over Aeron, with chaos switches. `feed-handler` arbitrates A/B, detects gaps, recovers from snapshots, builds MBO books and publishes top of book, depth, trades and book status on Aeron IPC. The latency harness measures venue publish to top of book out.

Out of scope (M3): external order entry (iLink style sessions), oms, gateway, core-cluster, risk.

## Decisions already made

- Dependencies: Aeron 1.53.3 (`aeron-client`, `aeron-driver`; depends on Agrona 2.6.1, matching our pin) and SnakeYAML Engine 3.2 for `config/instruments.yaml` (startup only). OpenHFT Affinity deferred to Linux tuning.
- A/B transport: unicast UDP to two localhost ports by default (multicast on macOS loopback is unreliable); channel URIs come from config so Linux can use multicast.
- Schema v1 adds messages only; v0 messages are unchanged, so v0 recordings still decode.

## Tasks (one session each)

| # | Task | FRS | Modules |
| --- | --- | --- | --- |
| 2.1 | Schema v1 (snapshot, top of book, depth, book status, feed handler trade) and instruments from YAML | MD.06, MD.07, MD.09 | schemas, common |
| 2.2 | Matching engine: price time priority, intrusive FIFO levels of pooled orders in a tick indexed array, MBO event sink; no Aeron | SIM.02 | venue-sim |
| 2.3 | Seeded Poisson flow, A/B publisher with channel sequence numbers, snapshot loop, chaos switches, definitions stream | MD.09 | venue-sim |
| 2.4 | MBO book: pooled orders in `Long2ObjectHashMap`, tick indexed levels, BBO and depth; matches a reference book | MD.04, MD.05 | feed-handler |
| 2.5 | A/B arbitration, gap detection, snapshot recovery with buffered replay, BookStatus, stale feed timer | MD.01, MD.02, MD.03, MD.12 | feed-handler |
| 2.6 | IPC output streams with receive and publish timestamps; `venue-sim` and `md-gateway` mains; embedded driver for tests | MD.07, MD.08 | feed-handler, venue-sim |
| 2.7 | End to end latency harness over Aeron; FRS acceptance tests | MD.01, MD.02 | bench, feed-handler |

## Done when

- `FR.MD.01 killing feed A mid session causes zero lost messages` passes.
- `FR.MD.02 injected gap is recovered and the book matches a reference build` passes.
- Both run on a seeded, fixed length session and are deterministic.
- `./gradlew build` (including `allocationTest`) is green locally and in CI; each per message method has a JMH benchmark.
- `./gradlew :bench:run` reports venue to top of book percentiles with the machine spec.
