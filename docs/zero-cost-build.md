# Zero Cost Rates Trading Platform (Portfolio Build)

Oct 11, 2026 · @Sudhan

## 1. Goal

Build the same event driven rates platform on one laptop, for $0, so that every line of the target job description maps to code you wrote, measured and can demo. The only thing you don't get is a real exchange, so you build one.

| Job description requirement | Module that gives you the hands on experience | Proof you will have |
| --- | --- | --- |
| Low latency, event driven systematic trading architecture for rates, in Java | Single writer pipeline on Aeron IPC with SBE: feed handler, strategy, risk, gateway | Pipeline running against a simulated CME style exchange |
| Minimize end to end signal to execution latency | Busy spin threads, pinned cores, zero allocation, hardware free timestamping with `System.nanoTime` and HdrHistogram | Latency report: p50, p99, p99.9 per stage, plus a before and after tuning chart |
| Quantitative execution algorithms, market impact reduction | Passive, implementation shortfall (Almgren Chriss) and curve leg executors | Simulated runs comparing algos on the same parent orders |
| Transaction cost analysis | TCA module: shortfall decomposition, markouts, fill rates | Nightly TCA report in Parquet and a notebook |
| Liquidity modeling | Depth, refill and event regime model fitted on real CME MBO data | Calibration notebook per contract |
| Multi threaded simulation and backtesting over multi TB replay data | Deterministic replay engine, sharded by day, parallel across cores | Throughput benchmark (events per second per core) and a scaling chart |
| Accurate fill probabilities | Queue position tracking on MBO, estimated queue on MBP, logistic fill model | Sim vs "live" fill rate reconciliation against your own exchange |
| Aeron or ZeroMQ messaging | Aeron IPC and UDP on the hot path, ZeroMQ for dashboards and Python | Both running in the same system with clear roles |
| SBE or Protobuf serialization | SBE for hot path and recordings, Protobuf for control and analytics | Shared schema repo with generated codecs |

A note on "multi terabyte": you won't have terabytes of real data for free, so the replay engine is designed and benchmarked to that scale using synthetic data, and validated for correctness on the real data you do have.

## 2. Firm component vs free substitute

Every paid piece of the firm design has a free stand in that exercises the same code path; the business logic and the Java you write are identical, only the edge of the system changes.

| Firm component | Free substitute | Why the skill still transfers |
| --- | --- | --- |
| CME Globex exchange | Your own matching engine (`venue-sim`) speaking CME style SBE messages: MDP 3.0 like market data out, iLink 3 like orders in | You write the exchange's protocol on both sides, so you understand sequencing, gaps, acks and fills better than most people who only consume them |
| Colocated feed over UDP multicast | Aeron UDP multicast on loopback, with A and B channels and injected packet loss | Same arbitration, gap detection and recovery logic |
| Live CME market data | Replay of real CME Treasury futures MBO bought with free Databento credits, plus a synthetic generator for volume | Real microstructure for calibration; synthetic data for scale |
| Kernel bypass NIC and hardware timestamps | Aeron IPC, busy spin, core pinning on your laptop; `System.nanoTime` stamps at each stage boundary | You still build and measure the latency budget per stage; the NIC part is a known swap |
| Treasury cash venues and client RFQ venues | Second simulated venue with a FIX interface (QuickFIX/J) and a simulated RFQ client | You still build the FIX gateway, SOR and RFQ responder |
| Aeron Cluster across data centers | Aeron Cluster with 3 nodes as local processes or Docker containers | Real Raft failover you can kill and watch recover |
| Kafka and Flink | Redpanda or Apache Kafka in Docker | Same event streams for TCA, PnL and audit |
| Terabytes of recordings on storage arrays | Local SSD with zstd compressed SBE files; synthetic multi day sets sized to your disk | The replay engine and sharding are what you are showing |
| Clearing, booking, regulatory reporting | Out of scope, or a stub that writes trade events to a Kafka topic | Mention it as a known extension in interviews |
| Cloud compute for backtests | Your laptop's cores, optionally the Oracle Cloud Always Free tier (now 2 Arm OCPUs and 12 GB RAM after a June 2026 cut, [InfoQ](https://infoq.com/news/2026/07/oracle-cloud-free-tier-limits/)) | Parallel sharding works the same on 8 cores or 800 |
| CI and code hosting | GitHub public repo with GitHub Actions (free for public repos) | Allocation tests, JMH benchmarks and replay checks run on every push |

## 3. Architecture

The system is the firm design with the outside world replaced by simulators you write: two venues, a data generator, and the same pinned four stage pipeline in the middle.

&#91;embedded content: zero cost architecture · one laptop, 2 simulated venues, 4 stage hot path\]

In replay mode venue-sim plays recorded CME data and matches your orders against it; in live mode it runs synthetic flow, so the pipeline never knows the difference.

## 3a. Process and service design (institutional style)

Real platforms split exactly this way: a small plain Java core that is sequenced, single threaded and event sourced, with Spring Boot services around it for everything people and other systems talk to. LMAX runs its exchange logic as one in memory Java thread with no framework or database ([Fowler](https://www.martinfowler.com/articles/lmax.html)). Adaptive builds matching engines, pre trade risk, RFQ workflows and smart order routers as Aeron Cluster services ([Adaptive](https://weareadaptive.com/2020/05/20/aeronhydra/)). A New York bank building a US rates e-trading platform asks for both low latency multithreaded Java and Spring Boot with Actuator ([job posting](https://brainsworkgroup.catsone.com/careers/9549/jobs/16038740-Java-eTrading-Consultant)).

&#91;embedded content: process and service topology · 6 plain Java processes, 4 Spring Boot services\]

Orders never touch a Spring process; Spring services only send commands into the core and read what it publishes.

| Process | Style | Built with | Talks over | State | Institutional equivalent |
| --- | --- | --- | --- | --- | --- |
| md-gateway | Plain Java, pinned agent | Aeron, Agrona, SBE | Venue multicast in, Aeron IPC out | Books in memory | Market data handler |
| trading-engine | Plain Java, single writer pipeline | Aeron, Agrona, SBE | Aeron IPC, cluster client | In memory | Strategy and autohedger engine |
| core-cluster | Aeron Cluster service, 3 nodes | Aeron Cluster | Cluster ingress and egress | Replicated log plus snapshots | OMS and risk "business logic processor" |
| venue-gateway | Plain Java | SBE over TCP, QuickFIX/J | Venue sessions | Sequence numbers on disk | Exchange connectivity |
| recorder | Plain Java | Aeron Archive | Every stream | Archive files | Tick and order capture |
| event-bridge | Plain Java | Aeron, Kafka client | Core events out to Kafka | None | Enterprise bus publisher |
| refdata-service | Spring Boot | Spring Web, Spring Data JPA | REST; snapshot to the core before the open | Postgres | Static data service |
| risk-admin-service | Spring Boot | Spring Security with Keycloak, Spring Data JPA | REST; limit changes to cluster ingress | Postgres (approval workflow) | Limits management |
| ops-gateway | Spring Boot | Spring Web, WebSocket, Actuator, Security | WebSocket to the console; ZeroMQ telemetry in; commands to cluster ingress | None | Trader UI backend |
| tca-service | Spring Boot | Spring Kafka, scheduling | Kafka in, REST out | Postgres and Parquet | Post trade analytics |

**Boundary rules**

1. Nothing between a market data packet and an order send runs in a Spring process.
2. Commands from the Spring side (limit change, pause, kill) enter the core only as SBE messages on Aeron Cluster ingress. They are sequenced and logged like any other event, so the audit trail and replays include them.
3. Reads go the other way: the core publishes events, and each Spring service builds its own view (Postgres tables, caches) from them. The core is the command side and the services are query sides, the CQRS split Fowler describes for LMAX.
4. Spring services can restart or redeploy at any time of day. Core processes start before the open with a warmup and change only in a release window.
5. SBE stays inside the core; Protobuf or JSON is used at the Spring edges, and each mapping lives in exactly one gateway.

**How you run it locally**

- Core: plain `java -jar` processes with tuned JVM flags and pinned threads, started by scripts or systemd units, like bare metal in colo.
- Spring services and infrastructure: one `docker-compose.yml` with Postgres, Redpanda, Keycloak, Prometheus and Grafana, like a bank's Kubernetes estate.
- Keycloak gives real SSO roles (trader, risk manager, support) that match the FRS actors.

**When to build each piece:** M2 loads instruments from a YAML file; M3 adds core-cluster for orders and positions; M5 adds event-bridge and tca-service; M6 adds refdata-service, risk-admin-service, ops-gateway and Keycloak.

## 4. Free data sources

Use real data to calibrate and validate, and synthetic data to stress and scale; never spend money on more history.

| Source | What you get | Used for | Cost |
| --- | --- | --- | --- |
| [Databento](https://databento.com/pricing) signup credits | $125 of historical data credits; enough for many days of MBO on a single CME product (their own example covers over a year of one product's MBO). Pull ZN (10y note futures) and ZF or ZT for a curve | Real order by order book, queue modeling, impact and liquidity calibration | $0 within credits; check cost with their cost API before every pull |
| FRED (St. Louis Fed) | Daily Treasury constant maturity yields, SOFR | Anchoring the synthetic generator's daily levels and curve shape | Free API key |
| TreasuryDirect and the Treasury fiscal data API | Auction dates and results | Event calendar for regime flags | Free |
| CME Group website | Contract specs, tick sizes, conversion factors, holiday calendars | Reference data | Free |
| Crypto exchange public feeds (for example Coinbase level2, no authentication for the public market data feed, [Coinbase docs](https://docs.cdp.coinbase.com/exchange/websocket-feed/overview)) | A real, 24/7, live order book stream | Exercising the live ingest path, reconnects and gap handling outside market hours | Free |
| Your own synthetic generator | A Hawkes process order flow model calibrated on the Databento days, driven by FRED yield levels | Terabyte scale replay sets, stress tests (FOMC style bursts), deterministic tests | Free |

**Rules for the data layer**

1. Convert every source into your own SBE schemas at ingest, so the rest of the system never knows where data came from.
2. Tag every recording with its source (`REAL`, `SYNTHETIC`, `LIVE_PROXY`) and never report a strategy PnL on synthetic data as if it were real.
3. Check Databento's license terms before publishing any raw data in a public repo; publish code and derived statistics, not the raw files.

## 5. Module design

Ten modules, each small enough to build and test on its own, wired together only through SBE messages on Aeron streams.

**venue-sim (the exchange you build)**

- Price time priority (FIFO) matching for futures; optional pro rata mode to mirror CME's Treasury option books later.
- Publishes incremental book updates and trades as SBE on Aeron UDP multicast, on an A and a B channel, with sequence numbers and a snapshot channel for recovery.
- Accepts orders over an iLink 3 style SBE session: negotiate, establish, new, cancel, replace, with acks, rejects and fills.
- Chaos switches: drop or reorder packets on one channel, delay acks, disconnect sessions, reject on throttle.
- Two modes: **replay** (feeds recorded market data and matches your orders against the replayed book, tracking your queue position) and **live** (drives a synthetic order flow and matches everything).

**feed-handler**

- A/B arbitration, gap detection, snapshot recovery, book stale flag.
- MBO book per instrument with fixed point prices; publishes top of book and depth on separate streams.

**pricing**

- Microprice fair value per contract; futures implied yields using conversion factor and a simple cheapest to deliver model; DV01 per contract and per curve bucket.

**strategy-api and strategies**

- Callback API with an injected clock, so the same code runs live, in paper mode and in backtests.
- Starter strategies: (1) order book imbalance signal on ZN, (2) ZF/ZN curve spread mean reversion with DV01 weighting.

**execution**

- Passive join and improve, implementation shortfall with an Almgren Chriss schedule, and a two leg curve executor.
- Every child order decision logs alpha, spread and modeled impact.

**risk**

- Max order size, position and DV01 limits, price collar around fair value, message throttle, loss limit, kill switch.

**oms and gateway**

- Order state machine identical to the FRS lifecycle; positions from fills; a drop copy stream from venue-sim for reconciliation.
- A QuickFIX/J gateway to a second simulated venue, so you also show FIX.

**recorder and backtester**

- Aeron Archive records every stream; a daily job rolls recordings into zstd files and Parquet.
- Backtester replays a day through the unmodified pipeline with a virtual clock and injected latency; a work stealing pool runs days and parameter sets in parallel.

**tca**

- Shortfall decomposition, markouts at 1 s to 5 min, passive fill rate by queue position, realized vs predicted impact; nightly refit of impact parameters.

**ops**

- Grafana dashboards fed by Prometheus; a small web console (or a JavaFX or terminal UI) to start, pause and kill strategies.

## 6. Free technology stack and laptop tuning

Every tool below is open source or free for a public project, and it matches the firm stack in the architecture doc one for one.

| Layer | Tool | Licence |
| --- | --- | --- |
| Language and runtime | OpenJDK 21 LTS (Eclipse Temurin), CI also on 25, Generational ZGC, Epsilon GC for allocation tests | GPLv2 with Classpath Exception |
| Messaging | Aeron (IPC, UDP, Archive, Cluster), Agrona | Apache 2.0 |
| Encoding | SBE (Real Logic), Protobuf | Apache 2.0, BSD |
| FIX | QuickFIX/J; Artio as a stretch goal | QuickFIX license, Apache 2.0 |
| Off hot path messaging | JeroMQ (pure Java ZeroMQ), Redpanda or Apache Kafka in Docker | MPL 2.0, BSL source available (free to self host), Apache 2.0 |
| Concurrency | LMAX Disruptor, JCTools, OpenHFT Java Thread Affinity | Apache 2.0 |
| Metrics | HdrHistogram, Prometheus, Grafana OSS | Public domain, Apache 2.0, AGPLv3 |
| Testing and profiling | JUnit 5, AssertJ, JMH, async profiler, JFR, JITWatch | Open source |
| Research | Python, polars, PyArrow, NumPy, statsmodels, QuantLib Python, JupyterLab, DuckDB | Open source |
| Build and CI | Gradle (Kotlin DSL), GitHub Actions on a public repo, Docker | Free |
| Coding assistant | Claude Code (already have) | Existing subscription |

**Laptop setup**

- Run Linux natively if you can (dual boot Ubuntu); WSL2 works for development but its scheduler and timers add jitter, so take final latency numbers on native Linux.
- Isolate cores for the hot path: boot with `isolcpus` and `nohz_full` on 2 to 4 cores, pin feed handler, strategy, risk and gateway threads with Java Thread Affinity.
- Set the CPU governor to `performance`, disable turbo and deep C states during benchmark runs so numbers are repeatable.
- JVM flags: `-XX:+AlwaysPreTouch`, large pages, a fixed heap, and a warmup replay before measuring.
- Measure with `System.nanoTime` at every stage boundary into HdrHistogram; report p50, p99, p99.9 and max, and always state the machine and settings next to the numbers.

**Honest latency expectations:** on a laptop, an in process Aeron IPC pipeline from book update to order message typically lands in the low single digit microseconds at p50, with a much wider tail than a tuned server. The value you show is the method (budget, measurement, tuning, before and after), not a headline number.

## 7. Build plan

Build the exchange and the hot path first, so you have a measurable latency story by week 10, then layer replay, execution and TCA on top.

&#91;embedded content\]

With Claude Code, use the FRS requirement IDs as task names (for example "implement FR.MD.01 to FR.MD.03") so each session has a clear, testable target.

## 8. Showcasing it

Recruiters skim the README and interviewers probe the numbers, so the repo has to make both easy.

**Repo layout**

```
rates-platform/
  schemas/        SBE and Protobuf definitions, generated codecs
  common/         fixed point math, clocks, instrument model
  venue-sim/      matching engine, MDP style feed, iLink style sessions
  feed-handler/   arbitration, gap recovery, books
  pricing/        microprice, implied yields, DV01
  strategy-api/   callbacks, injected clock
  strategies/     imbalance, curve spread
  execution/      passive, IS, curve executor
  risk/           pre trade checks, kill switch
  oms/ gateway/   state machine, positions, FIX gateway
  recorder/ sim/  archive tooling, replay and backtest runner
  tca/            Java metrics, Python notebooks
  bench/          JMH and end to end latency harness
  docs/           architecture, FRS, latency report
```

**Evidence to publish**

- Latency report: per stage HdrHistogram plots, the machine spec, tuning steps and the before and after effect of each.
- Zero allocation proof: an Epsilon GC CI job that replays a full day.
- Determinism proof: the same recording and seed give byte identical orders, checked in CI.
- Replay throughput: events per second per core and a scaling chart across 1 to all cores.
- Execution comparison: shortfall and markouts for passive vs IS vs aggressive on the same parent orders.
- Fill model calibration: predicted vs realized fill probability by queue position.
- A 3 minute demo video: replay a day, kill the Aeron Cluster leader mid run, show the kill switch.

**Resume bullets (fill in your measured numbers)**

- Built an event driven Java rates trading platform on Aeron and SBE with a zero allocation hot path, measuring X µs p99 from book update to order send.
- Implemented implementation shortfall and curve execution algorithms with a TCA framework, cutting simulated shortfall by X bp vs aggressive execution.
- Engineered a deterministic, multi threaded replay backtester at X million events per second, with queue aware fill modeling validated against a simulated CME style exchange.

**Interview angles**

- Why you built the exchange: it forced you to understand sequencing, recovery and fills from both sides.
- What would change at a firm: kernel bypass NICs, colocation, real venue certification, clearing and reporting, and the FRS phases you already wrote.
- Where your laptop numbers stop being meaningful, and how you would validate on real hardware.
