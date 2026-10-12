# Low Latency Rates Trading Architecture (Java)

Oct 10, 2026 · @Sudhan

## Overview

The design is a single writer, event sourced Java pipeline that targets under 10 µs p99 from market data packet in to order packet out. Every component, live or simulated, consumes the same SBE encoded event stream, so the strategy code that trades is byte for byte the code that is backtested.

**Products in scope**

- US Treasury cash, on the run and off the run, on dealer to dealer CLOBs and dealer to client RFQ venues
- Treasury and SOFR futures on CME (MDP 3.0 market data, iLink 3 order entry, both natively SBE)
- Interest rate swaps via RFQ on SEFs, mainly as hedges and curve legs

**Design principles**

1. One thread owns each piece of state; threads talk only through lock free ring buffers.
2. Zero allocation on the hot path, so the GC never runs during trading hours.
3. Everything is an event, recorded once, replayable forever.
4. Fixed point integer prices (Treasuries in 1/256 of a point), never `double` for prices.

**Latency budget (design targets, colocated, kernel bypass NIC)**

| Stage | Target p99 | Owner |
| --- | --- | --- |
| NIC to user space (kernel bypass) | 1 to 2 µs | Network stack |
| SBE decode and order book update | under 1 µs | Feed handler |
| Signal and fair value update | 1 to 3 µs | Strategy |
| Pre trade risk checks | under 1 µs | Risk gate |
| Order encode and send | under 1 µs | Order gateway |
| **Wire to wire total** | **under 10 µs** | Whole path |

The budget is the contract: each stage is measured with hardware timestamps and any regression fails the build.

## System architecture

Four pinned stages form the hot path, two Aeron services hold state and history, and everything analytical consumes the recording rather than the live path.

&#91;embedded content: system architecture · 4 stage hot path, state, recording, off path consumers\]

A tick crosses four cores and three ring buffers before an order leaves; nothing below the shaded box can slow it down.

**Event flow, step by step**

1. Feed handler takes the first copy of each packet from the A and B feeds, decodes SBE in place and updates the order book.
2. Strategy updates fair value (futures lead cash, curve and basis signals) and its execution algo decides post, cross or cancel.
3. Risk gate checks DV01, price collar and rate limits against state it owns, then passes or rejects.
4. Order gateway encodes the venue message and sends it; acks and fills flow back through the same path.
5. Aeron Archive records every event, and Aeron Cluster replicates positions and limits to a hot standby.
6. Backtester, TCA and dashboards read the recording, so research load never touches trading latency.

## Hot path engineering in Java

Java reaches single digit microseconds only when it behaves like C: no allocation, no locks, no context switches, and a JIT that is fully warm before the open.

**Threading model**

- One busy spinning thread per stage (feed handler, strategy, risk, gateway), each pinned to an isolated core (`isolcpus`, `nohz_full`, OpenHFT Affinity).
- Stages connect through single producer single consumer ring buffers (Agrona `OneToOneRingBuffer` or an LMAX Disruptor), never through `synchronized` or `BlockingQueue`.
- Single writer principle: the strategy thread alone mutates book and position state, so no field needs a lock or a CAS.
- Idle strategy is `BusySpinIdleStrategy` on the hot path and `BackoffIdleStrategy` on the control plane.

**Memory and GC**

- Messages are read in place with SBE flyweights over off heap `UnsafeBuffer`, so decoding creates no objects.
- Orders, book levels and events come from preallocated pools; collections are primitive (Agrona `Long2ObjectHashMap`, no boxing).
- Prove zero allocation by running the session under Epsilon GC in CI; any allocation eventually crashes the test.
- In production use Generational ZGC (JDK 21+) or Azul Prime as a safety net, with heap sized so no cycle happens intraday.
- Plan the move from `sun.misc.Unsafe` to the Foreign Function and Memory API (`MemorySegment`, final in JDK 22), since Unsafe memory access is being deprecated.

**JIT and CPU**

- Warm up before the open by replaying the prior session through the live code path with orders routed to a null gateway.
- Keep call sites monomorphic, use `final` classes, and check inlining with `-XX:+PrintCompilation` and JITWatch.
- `-XX:+AlwaysPreTouch`, huge pages, and cache line padding on hot counters to avoid false sharing.
- Data structures sized to stay in L1/L2: a Treasury book is a fixed array of price levels indexed by tick offset from the last trade.

**Network**

- Kernel bypass via Solarflare ef\_vi or OpenOnload (or an FPGA NIC for the feed), with hardware RX/TX timestamps for latency attribution.
- A/B multicast feed arbitration in the feed handler: take the first copy of each sequence number, detect gaps, recover from snapshot.

## Messaging and serialization

Aeron with SBE carries everything on the trading path; ZeroMQ with Protobuf carries everything that a human or a Python process reads. The split keeps the hot path deterministic and the research side flexible.

| Layer | Transport | Encoding | Why |
| --- | --- | --- | --- |
| Feed handler to strategy (same box) | Aeron IPC | SBE | Shared memory log, sub microsecond, no syscalls |
| Strategy to order gateway | Aeron IPC | SBE | Same, plus back pressure is explicit (`offer` returns a status) |
| Order gateway to CME | iLink 3 over TCP | SBE | Exchange native, no translation layer |
| Positions, OMS state, risk limits | Aeron Cluster (Raft) | SBE | Replicated, deterministic state machine with failover |
| Event recording for replay | Aeron Archive | SBE | Records the exact bytes the strategy saw, replays them at any speed |
| Analytics, TCA, dashboards, Python research | ZeroMQ PUB/SUB | Protobuf | Cross language, schema evolution, latency tolerant |
| Config and parameter pushes | ZeroMQ REQ/REP | Protobuf | Versioned, human auditable |

**Why SBE on the hot path:** fields sit at fixed offsets, so a flyweight reads a price with one memory load and no parsing or allocation. Protobuf uses varints and builds objects, which costs hundreds of nanoseconds and garbage.

**Why Aeron over Kafka or plain sockets:** Aeron gives reliable UDP multicast, shared memory IPC and archive replay in one library, with latency measured in microseconds rather than milliseconds.

**Schema rules:** one SBE schema repo, versioned, with the same message definitions used by live, sim and archive, so a replayed byte stream is always decodable.

## Technology stack

The stack is Java 21 LTS (also tested on Java 25) and the Real Logic toolchain (Aeron, Agrona, SBE, Artio) on the hot path, mainstream JVM and Python tooling everywhere else. Every version is pinned in one Gradle version catalog.

**Hot path (T0, T1)**

| Layer | Tool | Used for | Why this one |
| --- | --- | --- | --- |
| Runtime | OpenJDK 21 LTS (CI also on 25), Generational ZGC; Azul Platform Prime as an option | All Java processes | The LTS banks standardize on, with Generational ZGC; move to 25 and the FFM API as Aeron and Agrona leave sun.misc.Unsafe |
| Messaging | Aeron (IPC, UDP, Archive, Cluster) | Stage to stage transport, recording, replicated state | Microsecond latency, replay and Raft in one library |
| Data structures | Agrona | Ring buffers, off heap buffers, primitive maps, idle strategies | Built for zero allocation code |
| Pipelines | LMAX Disruptor, JCTools | Multi consumer fan out, specialized queues | Proven lock free patterns |
| Encoding | Simple Binary Encoding (SBE) | Hot path messages, CME MDP 3.0 and iLink 3 codecs | Fixed offsets, zero copy, CME native |
| FIX | Artio | Cash venue and client RFQ sessions | Low latency FIX engine built on Aeron |
| CPU control | OpenHFT Java Thread Affinity | Pin threads to isolated cores | Removes scheduler jitter |
| Network | AMD Solarflare NICs with OpenOnload or ef\_vi | Kernel bypass, hardware timestamps | Sub 2 µs NIC to user space |
| Latency stats | HdrHistogram | Per stage percentiles | Constant memory, accurate tails |
| Logging | Log4j2 garbage free mode or an Agrona based binary logger | Diagnostics without allocation | Keeps GC silent |

**Off the hot path (T2, T3, research)**

| Layer | Tool | Used for |
| --- | --- | --- |
| Services | Spring Boot | Reference data, limits admin, ops APIs |
| Streaming | Apache Kafka, Apache Flink | Trade events, real time risk and PnL aggregation |
| Control plane | JeroMQ (ZeroMQ), Protobuf, gRPC | Dashboards, config pushes, Python consumers |
| Storage | PostgreSQL, Parquet on object storage, DuckDB or ClickHouse | Reference data, research data, fast analytical queries |
| FIX (non latency) | QuickFIX/J | Drop copy and back office sessions |
| Research | Python, polars, PyArrow, NumPy, statsmodels, scikit-learn, QuantLib, Jupyter | Signal research, TCA, model calibration and validation |
| Batch compute | Kubernetes jobs (or Slurm), Apache Spark | Parallel backtests, nightly calibration |
| Ops console | React, TypeScript, WebSocket gateway | Trader and support UI |
| Observability | Prometheus, Grafana, OpenTelemetry, OpenSearch | Metrics, traces for services, log search |

**Build, test and delivery**

| Need | Tool |
| --- | --- |
| Build | Gradle (Kotlin DSL), multi module, version catalog |
| Unit and integration tests | JUnit 5, AssertJ, Testcontainers (services only) |
| Performance | JMH, async profiler, JFR, JITWatch, Epsilon GC allocation runs |
| Static checks | Error Prone, ArchUnit rules banning allocation prone APIs in hot path modules |
| CI/CD | GitHub Actions or Jenkins, Docker for services, Ansible for colo hosts, Terraform for cloud |
| Secrets | HashiCorp Vault |

**Gradle module layout**

- `schemas` (SBE and Protobuf definitions, generated codecs)
- `common` (fixed point math, clocks, instrument model)
- `marketdata` (feed handlers, books)
- `pricing` (fair value, curve, DV01)
- `strategy-api` and `strategies` (framework and implementations)
- `execution` (algos, SOR)
- `risk` (pre trade gate, limits)
- `oms` and `gateways` (state machine, iLink 3, FIX)
- `recording`, `sim` (archive tooling, matching simulator, backtest runner)
- `tca`, `ops-api` (off hot path services)

## Execution algorithms

The execution layer turns a parent order sized in DV01 into child orders that minimize expected impact plus risk, using a live liquidity model per instrument and venue.

**Market impact model.** Impact is calibrated per instrument on our own fills, starting from the square root law and adding a decay term so impact fades between child orders:

```latex
I(Q) = Y \, \sigma \sqrt{\frac{Q}{V}} \qquad G(t) = e^{-t/\tau}
```

Here Q is order size, V is expected volume over the horizon, σ is short horizon volatility in ticks, Y is a fitted constant, and τ is the decay time. The schedule follows Almgren Chriss: minimize expected cost plus λ times cost variance, where λ is the desk's urgency.

**Liquidity model.** For each instrument and venue the model tracks:

- Visible depth at each level and its refill rate after a sweep
- Hidden and iceberg liquidity, inferred from trades larger than displayed size
- Regime flags: economic data at 8:30 ET, Treasury auctions, FOMC, month end, roll periods
- Cross venue lead lag: futures usually lead cash, so futures moves feed the cash fair value

**Algorithms**

| Algo | Venue type | Logic |
| --- | --- | --- |
| Passive join and improve | CLOB | Rest at best bid or offer, requeue when fair value moves past a threshold |
| Implementation shortfall | CLOB | Almgren Chriss schedule, cross the spread only when urgency or alpha decay beats impact |
| Liquidity seeker | CLOB | Small iceberg slices, sweep only when depth is above its usual level |
| Curve and spread executor | Cash plus futures | DV01 weighted legs (for example 2s10s), work the less liquid leg first, hedge the liquid leg instantly to cap legging risk |
| Futures autohedger | CME | Offsets cash fills with futures by DV01 and conversion factor within microseconds |
| RFQ dealer selection | RFQ (off the runs, swaps) | Pick the number of dealers that balances price competition against information leakage, ranked by historical hit rate and markouts |

**Signal to execution coupling.** Every signal carries an expected alpha and its half life. The executor crosses the spread only when expected alpha left exceeds half the spread plus modeled impact; otherwise it posts.

## Transaction cost analysis

TCA runs off the hot path on the recorded event stream and feeds its fitted parameters back into the impact and liquidity models every night.

**Implementation shortfall decomposition**, all in ticks and in dollars per DV01 so cash and futures compare cleanly:

```latex
\text{IS} = \underbrace{(P_{\text{arrival}} - P_{\text{decision}})}_{\text{delay}} + \underbrace{(\bar{P}_{\text{fill}} - P_{\text{arrival}})}_{\text{impact + timing}} + \underbrace{(P_{\text{end}} - P_{\text{arrival}})(1 - f)}_{\text{opportunity}}
```

Here f is the filled fraction of the parent order (signed by side in the implementation).

**Metrics per fill, child and parent**

| Metric | What it tells you |
| --- | --- |
| Slippage vs arrival mid | Total cost of the decision to trade now |
| Markouts at 1 s, 10 s, 60 s, 5 min | Adverse selection: did the price move against us after we filled |
| Fill rate and time to fill for passive orders | Whether queue position assumptions hold |
| Realized vs predicted impact | Calibration error of the impact model |
| Signal to ack and signal to fill latency | Whether alpha decayed before we got there |
| RFQ hit rate and cover distance | Dealer quality and information leakage |

**Feedback loop**

1. Nightly job regresses realized impact on size, spread, depth and regime to refit Y and τ.
2. Markouts flag toxic venues and times; the router down weights them.
3. Live fill rates are compared with simulated fill rates for the same orders; a gap means the backtester's queue model needs recalibration.

## Backtesting and market replay

The backtester replays recorded SBE bytes through the unmodified live strategy, against a simulated matching engine that tracks our queue position, and parallelizes across days and parameter sets rather than within a day.

**Data layer**

- Raw capture: Aeron Archive recordings plus pcap with NIC hardware timestamps, compressed with zstd in daily files per venue.
- Research copy: the same events normalized to Parquet, partitioned by date, venue and instrument, for Python and Spark analysis.
- Replay reads raw files through memory mapped `MappedByteBuffer` or `MemorySegment`, so a day streams at disk speed with no deserialization step.

**Engine design**

1. A reader thread decodes the merged, timestamp ordered event stream into a ring buffer.
2. A single simulation thread drives a virtual clock, the strategy, and the simulated exchange, so each run is fully deterministic and reproducible from its seed.
3. Order latency is injected from the measured live distribution (our wire to wire plus venue round trip), not a constant.
4. The simulated exchange supports FIFO and pro rata matching per product, configured from the venue's rules.

**Scaling to multi terabyte datasets**

- Shard by trading day (cash and futures together, since strategies trade both); each shard is one single threaded deterministic job.
- A work stealing pool runs shards across all cores on a node, and a scheduler (Kubernetes or Slurm) fans out across nodes.
- Parameter sweeps multiply shards; results stream back as Protobuf over ZeroMQ into a results store.
- Throughput rule of thumb: the replay should run far faster than real time per core, so a year of sessions finishes overnight.

**Fill probability modeling**

With full order by order data (MBO), we know exactly how much volume sits ahead of us. With only price level data, queue position is estimated: our order joins at the back, trades consume from the front, and cancels at the level are assumed to come from ahead of us in proportion to the share of the queue ahead.

```latex
P(\text{fill by } T) = P\left( V_{\text{trade}}(T) + C_{\text{ahead}}(T) \ge q \right), \qquad C_{\text{ahead}} \approx C_{\text{level}} \cdot \frac{q}{D}
```

Here q is queue ahead of us, D is total depth at the level, V is traded volume at our price, and C is cancelled volume. A logistic model on features such as q/D, book imbalance, spread and trade rate then gives the probability for any resting order.

**Validation:** every live order is also run through the simulator; the backtester is trusted only while simulated and live fill rates agree within tolerance per instrument.

## Risk, resilience and observability

Risk checks sit inline on the hot path and cost under a microsecond because every limit is a precomputed integer compare against state the risk thread already owns.

**Pre trade risk gate** (required for market access under SEC Rule 15c3-5)

- Max order size and max open exposure in DV01 per instrument, per curve bucket and in total
- Price collars around fair value, to stop fat finger and stale price orders
- Message rate and order to trade ratio throttles per venue
- Kill switch that cancels all and blocks new orders, reachable by a human and by automated loss and latency triggers

**Resilience**

- Positions, open orders and limits live in Aeron Cluster, so a standby node takes over with identical state.
- Feed gaps trigger snapshot recovery; the strategy pulls quotes while its book is not trusted.
- Drop copy from each venue is reconciled against the OMS in near real time.
- On restart, state is rebuilt by replaying the Aeron Archive from the last snapshot.

**Observability without touching latency**

- Hardware timestamps at NIC ingress and egress, plus a nanosecond tap at each stage boundary.
- Latencies recorded into HdrHistogram per stage; percentiles published every second over ZeroMQ.
- Logging is binary, written to a ring buffer and formatted by a separate thread, never string building on the hot path.
- Alerts on p99 drift, GC events (there should be none), queue depth and feed gaps.

## How this compares with fintech and bank platforms

The design so far is a prop style trading engine; a bank rates desk is mainly a market maker that prices clients, warehouses risk, and hedges. The engine is the right core, but a bank wraps it in client pricing, inventory management, post trade and control layers that dominate its size.

| Dimension | This design | Mid scale fintech (broker, neobank, crypto venue) | Tier 1 bank rates e-trading |
| --- | --- | --- | --- |
| Business model | Systematic strategies on CLOB and futures | Order routing and execution for retail or small institutions | Client market making (streaming and RFQ), central risk book, hedging on D2D venues |
| Critical latency | under 10 µs wire to wire | 1 to 50 ms, API and UI bound | under 10 µs for hedging, 100 µs to a few ms for client quoting |
| Stack | Java, Aeron, SBE, single writer | Java, Go or Kotlin microservices, Kafka, gRPC | Java or C++ hot path, FPGA feeds, plus legacy booking and risk systems |
| State | Aeron Cluster, in memory | Postgres, Redis, event store on Kafka | In memory engines, plus golden source books of record and a firmwide risk store |
| Deployment | Colocated bare metal | Cloud, Kubernetes, autoscaling | Colo for trading, private data centers for booking and risk, cloud for analytics |
| Throughput driver | Market data rate in bursts | Number of users and API calls | Market data plus thousands of client RFQs and streams per second |
| Risk controls | Pre trade gate, kill switch | Balance and margin checks, fraud | Multi level limits (trader, desk, legal entity), credit checks per client, firmwide kill switch |
| Post trade | Drop copy reconciliation | Ledger, payments, statements | Booking, confirmation, clearing (FICC, LCH, CME), settlement, regulatory reporting |
| Governance | CI performance gates | SOC 2, change control | Model risk validation (SR 11-7), Volcker metrics, algo inventory and approval, audit trail |

**Gaps this reveals in the current design**

1. No client facing layer: nothing prices clients or auto quotes RFQs.
2. No central risk book: strategy positions are not netted with client flow before hedging.
3. Single venue view: no smart order router or consolidated book across cash venues.
4. Stops at the fill: booking, clearing and reporting are absent.
5. Controls are single strategy, not firmwide; nothing enforces client credit or legal entity limits.

## Extended architecture at institutional scale

The original engine becomes tier T0 inside a four tier platform: client pricing above it, firmwide control and post trade below it, all fed by the same event log.

&#91;embedded content: institutional architecture · 4 latency tiers around the original execution core\]

Client RFQs enter at T1, net in the central risk book, and only the residual risk reaches the T0 hedger and router.

**New components and how to build them**

- **Client quoting engine:** prices streams and RFQs off curve fair value plus a skew from current inventory, client tier and size. Auto quotes inside limits; larger or unusual requests go to a trader.
- **Central risk book:** one position per curve bucket, netting client trades, strategy positions and hedges. It decides whether to internalize, hedge now in futures, or work the risk over time.
- **Curve fair value service:** builds a Treasury and swap curve from futures, on the run cash and SOFR swaps, republished on every relevant tick so quotes and algos share one price.
- **Smart order router:** a consolidated book across cash CLOBs, routing by expected fill probability, fees and markouts from TCA.
- **Firmwide limits and credit:** limits set per trader, desk and legal entity, plus per client credit lines; T0 and T1 keep local copies that are decremented in process and reconciled every few milliseconds.
- **Event backbone:** Aeron Archive remains the low latency log; a bridge republishes trade and position events to Kafka for T2 and T3 consumers, giving one ordered history for risk, PnL, surveillance and audit.
- **Post trade:** booking into the books of record, clearing submission, confirmations and regulatory reports, all built idempotently off the event stream so they can be replayed.

**Operational extensions**

- Active and standby sites in separate data centers, with Aeron Cluster replicating state and a tested failover runbook.
- Algo inventory and approval: every strategy and quoting model registered, versioned, validated and switchable off individually.
- CI performance gates on T0 and T1, and canary releases with a shadow mode that quotes and trades on paper before going live.

## Latency tiers and regulatory drivers

Not everything should be fast: putting each component in the right tier is what keeps a bank system both quick and auditable.

| Tier | Target (design) | Components | Tech |
| --- | --- | --- | --- |
| T0 hot path | under 10 µs | Feed handlers, hedger, CLOB execution, pre trade risk | Pinned Java or C++, Aeron IPC, SBE, kernel bypass, FPGA feeds |
| T1 client pricing | 100 µs to low ms | Streaming prices, RFQ auto quoter, skew, central risk book | Java, Aeron UDP, SBE internally, FIX to client venues |
| T2 near real time | ms to seconds | Firmwide risk aggregation, PnL, credit limits, surveillance alerts | Kafka or Aeron, Flink or Java services, Protobuf or Avro |
| T3 batch and analytics | minutes to overnight | TCA, backtests, model recalibration, regulatory reports | Spark, Parquet, Python, cloud |

**Regulatory drivers that shape the architecture**

- SEC Treasury clearing mandate: eligible cash Treasury trades must be centrally cleared by Dec 31, 2026 and repo by June 30, 2027, which forces a clearing submission path and margin tracking ([SIFMA](https://www.sifma.org/explore-issues/treasury-clearing)).
- FINRA TRACE: member broker dealers report Treasury trades, so every fill needs a reporting record.
- CFTC swap reporting: swap hedges go to a swap data repository with real time public dissemination.
- SEC Rule 15c3-5: pre trade risk controls for market access, already covered by the risk gate.
- Volcker Rule: a bank desk trades under the market making exemption, so inventory must track expected client demand and be measured daily.
- SR 11-7 model risk: pricing, impact and fill models need documentation, independent validation and monitoring.

## Interview talking points and tradeoffs

Desk strats will probe whether you understand why each choice was made, so lead with the tradeoff, then the number.

| Likely question | Crisp answer |
| --- | --- |
| Why Java and not C++? | Faster to build and safer to change; with zero allocation and warm JIT it reaches single digit µs. C++ or FPGA only where the last microsecond pays, such as the feed handler. |
| How do you handle GC? | Don't create garbage. Prove it under Epsilon GC in CI; run ZGC or Azul Prime in production as insurance. |
| Why single writer? | No locks, no contention, predictable cache behavior, and deterministic replay for free. |
| SBE vs Protobuf? | SBE for fixed layout, zero copy hot path messages (CME speaks it natively); Protobuf for cross language, evolving control and analytics messages. |
| Aeron vs Kafka? | Kafka is for durable, millisecond analytics streams; Aeron gives µs IPC and multicast plus archive replay. |
| How do you trust a backtest? | Same code, recorded bytes, queue aware fills, injected real latency, and continuous live vs sim fill reconciliation. |
| How is rates different from equities? | Risk is in DV01 not shares, futures lead cash, liquidity clusters in on the runs, scheduled events dominate, and much flow is RFQ not CLOB. |
| How would you cut latency further? | Measure first with hardware timestamps, then remove a thread hop, move decode to FPGA, or colocate the strategy on the feed core. |

**Projects to tie in:** Clearwater market data processing at scale (maps to the replay pipeline) and three years of data engineering at Société Générale (maps to the recording and TCA store).
