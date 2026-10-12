# Rates Trading Platform FRS

Oct 11, 2026 · @Sudhan

Functional Requirements Specification for the Java low latency rates trading platform described in the architecture design doc.

## 1. Introduction

This FRS defines what each component of the rates trading platform must do, in testable statements that developers build to and QA signs off against. The architecture design doc explains how the system is shaped; this document is the contract for behavior.

**Scope by phase**

| Phase | In scope | Out of scope until later |
| --- | --- | --- |
| Phase 1 MVP | CME Treasury futures market data and order entry, order book, one systematic strategy, pre trade risk, recording, replay backtester, basic TCA, ops console | Cash venues, client flow, post trade |
| Phase 2 | Treasury cash CLOBs, smart order router, curve fair value across cash and futures, client quoting, central risk book | Swaps, regulatory reporting automation |
| Phase 3 | Swap hedging via RFQ, booking and clearing integration, regulatory reporting, firmwide limits | None planned |

**Conventions**

- Requirement IDs follow `FR.<module>.<number>` for functional and `NFR.<area>.<number>` for nonfunctional requirements; IDs never get reused.
- Priority uses MoSCoW: **Must** (release blocking), **Should** (expected, can slip one release), **Could** (nice to have).
- Each requirement has an acceptance criterion that a test case maps to one to one.
- Phase tags: P1, P2, P3 match the table above.

**Definitions**

| Term | Meaning |
| --- | --- |
| DV01 | Dollar change in value for a 1 bp parallel move in yield |
| CLOB | Central limit order book |
| RFQ | Request for quote |
| CRB | Central risk book, the netted desk position across client and strategy flow |
| MBO / MBP | Market by order (every order visible) / market by price (aggregated levels) |
| Wire to wire | Time from market data packet arriving at the NIC to the order packet leaving it |
| Tick | Minimum price increment of an instrument |

## 2. Actors and system context

Six human roles and six external system types interact with the platform; every action a human takes is permissioned by role and written to the audit log.

**Human actors**

| Actor | Main goals | Key permissions |
| --- | --- | --- |
| Trader | Run strategies, manage positions, intervene on orders | Start, pause, stop strategies; cancel orders; adjust parameters within approved bounds |
| Quant researcher | Build and backtest strategies, calibrate models | Run backtests; submit strategy versions for approval; no live order rights |
| Risk manager | Set and monitor limits | Create and change limits; trigger kill switch; read all positions |
| Production support | Keep the system healthy | Restart components; view logs and metrics; no trading parameters |
| Compliance | Surveillance and audit | Read only on orders, fills, audit log, alerts |
| Administrator | Users, roles, reference data | Manage entitlements and static data; four eyes approval required |

**External systems**

| System | Direction | Protocol | Phase |
| --- | --- | --- | --- |
| CME Globex market data | Inbound | MDP 3.0, SBE over UDP multicast | P1 |
| CME Globex order entry | Both | iLink 3, SBE over TCP | P1 |
| Treasury cash CLOBs | Both | Venue native binary or FIX | P2 |
| Client RFQ venues (D2C) | Both | FIX | P2 |
| Clearing houses and booking systems | Outbound | FIX, venue APIs, internal messaging | P3 |
| Reference data vendor | Inbound | Vendor API, daily files | P1 |
|  |  |  |  |

## 3. Technology constraints

Developers must build with the stack below; any substitution needs an architecture review. Rationale for each choice lives in the architecture design doc's technology stack section.

| ID | Constraint | Applies to |
| --- | --- | --- |
| TC.01 | Java 21 LTS (OpenJDK), also tested on Java 25, Gradle multi module build with a version catalog pinning every dependency | All Java components |
| TC.02 | Aeron (transport, Archive, Cluster) and Agrona for all inter component messaging and data structures on the hot path | T0, T1 |
| TC.03 | SBE for every hot path message, schemas in one versioned repo, codecs generated at build time | T0, T1, recording |
| TC.04 | Artio for low latency FIX sessions; QuickFIX/J allowed only for drop copy and non latency sessions | Gateways |
| TC.05 | No allocation, locks, reflection, lambdas capturing state, or string building in hot path code | T0, T1 |
| TC.06 | Protobuf for control plane and analytics messages; JeroMQ (ZeroMQ) for research and dashboard fan out; Kafka for T2 and T3 event streams | Off hot path |
| TC.07 | Spring Boot permitted only for T2 and T3 services, never in T0 or T1 processes | Services |
| TC.08 | Python 3 with polars, PyArrow, NumPy, statsmodels and QuantLib for research and model validation | Research |
| TC.09 | Parquet for normalized research data; raw SBE recordings retained as the source of truth | Data |
| TC.10 | JUnit 5, JMH, async profiler, JFR and Epsilon GC runs are mandatory in CI | All |
| TC.11 | Prometheus and Grafana for metrics, HdrHistogram for latency, Log4j2 in garbage free mode or a binary logger for logs | All |
| TC.12 | Linux (RHEL family) on bare metal in colo with kernel bypass NICs for T0; containers on Kubernetes for T2, T3 and backtest workers | Infrastructure |

## 4. Market data and order book (MD)

The feed handler must turn venue packets into a correct, gap free order book per instrument and publish book updates to subscribers in under 1 µs p99.

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.MD.01 | Subscribe to A and B multicast feeds per channel and process the first copy of each sequence number | Must | P1 | Killing feed A mid session causes zero lost messages |
| FR.MD.02 | Detect sequence gaps and recover via the venue's snapshot or recovery channel | Must | P1 | Injected gap is recovered and the book matches a reference build |
| FR.MD.03 | Mark an instrument's book as untrusted during recovery and publish that state | Must | P1 | Strategies receive a BookStale event within 1 µs of gap detection |
| FR.MD.04 | Build MBO books where the venue provides them, and MBP books otherwise | Must | P1 | Book state equals the venue reference at every snapshot over a full replayed day |
| FR.MD.05 | Decode SBE messages in place with flyweights, no object creation | Must | P1 | Zero allocations under Epsilon GC for a full replayed session |
| FR.MD.06 | Represent prices as fixed point longs in tick units per instrument | Must | P1 | No `double` price fields in book or event schemas |
| FR.MD.07 | Stamp each event with NIC hardware receive time and handler publish time | Must | P1 | Both timestamps present on 100% of book events |
| FR.MD.08 | Publish trade, top of book and full depth updates on separate Aeron streams | Should | P1 | Consumers can subscribe to top of book only |
| FR.MD.09 | Handle instrument definition messages, including new contracts and roll dates | Must | P1 | A newly listed contract is tradable without restart |
| FR.MD.10 | Normalize Treasury cash venue feeds into the same book interface | Must | P2 | Strategy code is unchanged when subscribing to a cash instrument |
| FR.MD.11 | Build a consolidated cash book across venues, tagged by venue | Must | P2 | Consolidated best bid and offer equals the best across venue books |
| FR.MD.12 | Detect stale feeds (no message for a configured interval during market hours) and alert | Must | P1 | Alert fires within the configured interval plus 100 ms |

## 5. Curve pricing and strategy framework (PX, STR)

Pricing must give every consumer one consistent fair value per instrument, and the strategy framework must let researchers ship a strategy without touching threading, messaging or venue code.

**Curve fair value (PX)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.PX.01 | Compute a fair value per futures contract from its own book (microprice or weighted mid) | Must | P1 | Matches the reference Python implementation to 0.01 tick on a replayed day |
| FR.PX.02 | Compute futures implied cash prices using the cheapest to deliver, conversion factor and carry | Must | P2 | Matches QuantLib validation within 0.1 bp yield |
| FR.PX.03 | Build an intraday Treasury curve across tenors from futures and on the run cash | Must | P2 | Curve refresh under 5 µs p99 on a relevant tick |
| FR.PX.04 | Compute DV01 per instrument and per curve bucket (2y, 5y, 10y, 30y) | Must | P1 | Matches risk system DV01 within 0.5% |
| FR.PX.05 | Publish fair value, DV01 and a confidence flag on one stream consumed by strategies, quoting and risk | Must | P1 | All consumers use the same sequence number for a given fair value |
| FR.PX.06 | Freeze fair value and flag it stale when inputs are untrusted | Must | P1 | Stale input produces a stale flag within one event |

**Strategy framework (STR)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.STR.01 | Provide a strategy API with callbacks for book, trade, fair value, order and timer events | Must | P1 | A sample strategy compiles against the API alone |
| FR.STR.02 | Run identical strategy code in live, paper and backtest modes, switched by configuration | Must | P1 | Same input stream gives identical orders in backtest and paper mode |
| FR.STR.03 | Drive all strategy time from an injected clock, never system time | Must | P1 | Static analysis rejects `System.currentTimeMillis` and `nanoTime` in strategy modules |
| FR.STR.04 | Load parameters from versioned config; allow live changes within approved bounds | Must | P1 | Out of bounds change is rejected and audited |
| FR.STR.05 | Support start, pause (cancel working orders, keep state), and stop per strategy | Must | P1 | Pause cancels all working orders within 1 ms |
| FR.STR.06 | Express every signal with expected alpha (ticks) and half life | Should | P1 | Signal events carry both fields |
| FR.STR.07 | Expose per strategy position, PnL and order counts to the ops console | Must | P1 | Values match the OMS within one second |
| FR.STR.08 | Support curve and basis strategies with multiple legs and DV01 weights | Must | P2 | A 2s10s strategy holds net DV01 within the configured tolerance |

## 6. Execution algorithms and smart order routing (EXE, SOR)

Execution must turn a parent order in DV01 or contracts into child orders that minimize expected impact plus risk, and must explain every child order decision in the recorded log.

**Execution algorithms (EXE)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.EXE.01 | Accept parent orders with side, quantity or DV01, urgency, limit price, and end time | Must | P1 | Parent orders without a limit or end time are rejected |
| FR.EXE.02 | Passive algo: join or improve best price, requeue when fair value moves past a threshold | Must | P1 | Requeue happens within one fair value event of threshold breach |
| FR.EXE.03 | Implementation shortfall algo following an Almgren Chriss schedule with urgency λ | Must | P1 | Realized schedule tracks plan within 10% of quantity per interval in simulation |
| FR.EXE.04 | Cross the spread only when remaining alpha exceeds half spread plus modeled impact | Must | P1 | Every aggressive child order logs alpha, spread and impact inputs |
| FR.EXE.05 | Liquidity seeking algo using iceberg slices and opportunistic sweeps | Should | P2 | Displayed size never exceeds the configured slice |
| FR.EXE.06 | Multi leg executor that holds net DV01 within tolerance and works the less liquid leg first | Must | P2 | Legging exposure never exceeds tolerance for longer than the configured time |
| FR.EXE.07 | Load impact and liquidity model parameters per instrument from the TCA calibration store | Must | P1 | Parameters versioned, loaded at start, reloadable intraday |
| FR.EXE.08 | Pause behavior around scheduled events (data releases, auctions) from a configured calendar | Should | P1 | Algo goes passive only during configured windows |
| FR.EXE.09 | Report parent order progress: filled, remaining, average price, slippage vs arrival | Must | P1 | Ops console shows progress updated at least every second |

**Smart order router (SOR)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.SOR.01 | Route child orders across cash venues using the consolidated book | Must | P2 | Aggressive orders take best price first across venues |
| FR.SOR.02 | Score venues by fill probability, fees and markouts from TCA | Should | P2 | Scores refresh daily and are visible in the console |
| FR.SOR.03 | Respect per venue rate limits and session state | Must | P2 | No order is sent to a venue whose session is down |
| FR.SOR.04 | Record the routing decision and alternatives considered for each order | Must | P2 | Best execution report can be produced from logs alone |

## 7. Order management and venue gateways (OMS, GW)

The OMS is the single source of truth for every order's state; gateways translate between OMS events and venue protocols and never hold their own order state.

&#91;embedded content: order lifecycle · 8 states, 3 terminal\]

FR.OMS.02 tests every arrow above and rejects any transition not drawn.

**Order management (OMS)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.OMS.01 | Assign a unique, monotonic internal order ID and map it to venue IDs | Must | P1 | No ID collision across restarts and failovers |
| FR.OMS.02 | Enforce the order state machine; reject invalid transitions and alert | Must | P1 | Fuzz test of venue message orderings never produces an invalid state |
| FR.OMS.03 | Support new, cancel, and cancel replace for limit and IOC orders | Must | P1 | Each type round trips against the CME certification environment |
| FR.OMS.04 | Maintain positions per instrument, strategy, book and desk from fills | Must | P1 | Positions equal drop copy totals at end of day |
| FR.OMS.05 | Replicate order and position state through Aeron Cluster | Must | P1 | Leader kill causes failover with no lost or duplicated order |
| FR.OMS.06 | Handle unknown or late venue messages (fills after cancel, unsolicited cancels) | Must | P1 | All cases from the venue certification script pass |
| FR.OMS.07 | Reconcile against venue drop copy continuously and flag breaks | Must | P1 | Break raised within 5 seconds of mismatch |
| FR.OMS.08 | Cancel all working orders on a strategy pause, session loss or kill switch | Must | P1 | No working orders remain after the venue acks the mass cancel |

**Venue gateways (GW)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.GW.01 | Implement CME iLink 3 session management: negotiate, establish, sequence, retransmit | Must | P1 | Passes CME certification |
| FR.GW.02 | Enable cancel on disconnect on every venue session that supports it | Must | P1 | Session drop leaves no resting orders at the venue |
| FR.GW.03 | Enforce venue message throttles locally before the venue does | Must | P1 | Venue never rejects for rate limits in a stress test |
| FR.GW.04 | Persist outbound sequence numbers so restarts resume cleanly | Must | P1 | Restart mid session reconnects without a sequence reset |
| FR.GW.05 | Provide FIX gateways (Artio) for cash venues and client RFQ venues | Must | P2 | Each venue passes its conformance test |
| FR.GW.06 | Stamp hardware TX time on every outbound order | Must | P1 | Wire to wire latency reportable per order |

## 8. Pre trade risk, limits and kill switch (RISK)

Every order must pass the risk gate before it reaches a gateway; the gate fails closed, so if it cannot evaluate an order, the order is rejected.

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.RISK.01 | Check max order size per instrument in contracts and DV01 | Must | P1 | Oversized order rejected with reason code, never sent |
| FR.RISK.02 | Check projected position (current plus working plus new) against instrument, bucket, strategy and desk limits | Must | P1 | Rejection when any level would breach |
| FR.RISK.03 | Reject prices outside a collar around fair value, in ticks per instrument | Must | P1 | Order 1 tick beyond collar rejected |
| FR.RISK.04 | Reject orders when fair value or the book is stale | Must | P1 | No orders sent during an injected feed gap |
| FR.RISK.05 | Enforce message rate and order to trade ratio limits per strategy and venue | Must | P1 | Throttle engages before the venue limit |
| FR.RISK.06 | Detect self match risk and block orders that would cross own resting orders | Must | P1 | No self trade in a crossing test |
| FR.RISK.07 | Enforce intraday loss limits per strategy and desk; breach pauses the strategy | Must | P1 | Pause within 1 ms of PnL breach |
| FR.RISK.08 | Kill switch at strategy, desk and global level: cancel all, block new orders | Must | P1 | Global kill leaves zero working orders, confirmed by venue acks |
| FR.RISK.09 | Kill switch reachable from the console, a hardware button or API, and from automated triggers | Must | P1 | Each path tested in every release |
| FR.RISK.10 | Limit changes require four eyes approval and take effect without restart | Must | P1 | Unapproved change has no effect; approved change is live in under 1 second |
| FR.RISK.11 | Log every check result with inputs for audit | Must | P1 | Any rejection can be explained from the log |
| FR.RISK.12 | Apply per client credit limits on client trades | Must | P2 | Quote to a client over limit is not sent |
| FR.RISK.13 | Sync local limits with firmwide limits and legal entity limits | Must | P3 | Local usage reconciles with firmwide every few milliseconds |

## 9. Recording, replay and backtesting (REC, SIM)

The system must record every event exactly once and be able to replay any session, at any speed, through unmodified strategy code with deterministic results.

**Recording (REC)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.REC.01 | Record all inbound market data, internal events and outbound orders to Aeron Archive | Must | P1 | Byte count recorded equals byte count published for a full day |
| FR.REC.02 | Capture raw pcap with hardware timestamps alongside the archive | Should | P1 | Pcap and archive can be aligned by sequence and time |
| FR.REC.03 | Roll recordings daily into compressed files per venue with checksums | Must | P1 | Checksum verified on every read |
| FR.REC.04 | Convert recordings to Parquet partitioned by date, venue and instrument overnight | Must | P1 | Parquet row counts equal recorded event counts |
| FR.REC.05 | Retain raw recordings for the regulatory retention period on tiered storage | Must | P1 | Retention policy enforced automatically |

**Backtesting and simulation (SIM)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.SIM.01 | Replay recorded SBE streams through the live strategy code with a virtual clock | Must | P1 | Same recording and seed give byte identical output across runs |
| FR.SIM.02 | Simulate venue matching with FIFO and pro rata algorithms, configured per product | Must | P1 | Matches venue fills on recorded own orders for at least 95% of cases (target, to be calibrated) |
| FR.SIM.03 | Track queue position from MBO data; estimate it from MBP data with a configurable cancel ahead model | Must | P1 | Both modes selectable per run |
| FR.SIM.04 | Inject order latency sampled from the measured live distribution | Must | P1 | Latency distribution in sim matches live within tolerance |
| FR.SIM.05 | Model fill probability with a fitted logistic model on queue and book features | Should | P2 | Model calibration report produced per instrument |
| FR.SIM.06 | Shard runs by trading day and parameter set; run shards in parallel across cores and nodes | Must | P1 | A month of sessions completes on the cluster within the agreed overnight window |
| FR.SIM.07 | Produce a standard result set: PnL, fills, markouts, latency, risk usage | Must | P1 | Results queryable from Python by run ID |
| FR.SIM.08 | Shadow every live order through the simulator and report sim vs live fill rate | Must | P1 | Daily report with per instrument gap |
| FR.SIM.09 | Version every run with code commit, config, data range and model parameters | Must | P1 | Any past run can be reproduced exactly |

## 10. Transaction cost analysis (TCA)

TCA must measure the cost of every fill against standard benchmarks and feed calibrated impact and venue parameters back to execution each night.

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.TCA.01 | Compute slippage vs decision price, arrival mid, and interval VWAP for every child and parent order | Must | P1 | Values match a reference Python calculation exactly |
| FR.TCA.02 | Decompose implementation shortfall into delay, impact plus timing, and opportunity cost | Must | P1 | Components sum to total shortfall |
| FR.TCA.03 | Compute markouts at 1 s, 10 s, 60 s and 5 min, in ticks and dollars per DV01 | Must | P1 | Markouts available by next morning for all fills |
| FR.TCA.04 | Compute passive fill rate and time to fill by instrument, venue, time of day and queue position | Must | P1 | Report sliceable by each dimension |
| FR.TCA.05 | Refit impact model parameters (Y, τ) nightly by instrument and regime | Must | P1 | New parameters versioned and require approval before live use |
| FR.TCA.06 | Measure realized vs predicted impact and alert on drift beyond tolerance | Should | P1 | Alert fires when error exceeds the configured band for 3 days |
| FR.TCA.07 | Produce venue scorecards for the SOR | Should | P2 | Scores published to the calibration store daily |
| FR.TCA.08 | Compute RFQ hit rate, cover distance and post trade markouts per client and dealer | Must | P2 | Report available per client tier |
| FR.TCA.09 | Expose all TCA results to Python and dashboards via Parquet and Protobuf | Must | P1 | A notebook can load any day's TCA in one call |

## 11. Client quoting and central risk book (QT, CRB), phase 2

Client quoting must answer RFQs and stream prices automatically within limits, and the central risk book must net all desk risk before any hedge reaches the market.

**Client quoting (QT)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.QT.01 | Receive RFQs from D2C venues and respond within the venue's time window | Must | P2 | 99.9% of eligible RFQs answered in time |
| FR.QT.02 | Price = fair value plus spread by client tier, size and volatility, plus inventory skew | Must | P2 | Quote breakdown logged for every response |
| FR.QT.03 | Auto quote only within size, client and instrument rules; route others to a trader with a suggested price | Must | P2 | Out of rule RFQs never auto quoted |
| FR.QT.04 | Stream executable prices to configured clients, with last look rules where the venue allows | Should | P2 | Streams pulled within 1 ms when fair value goes stale |
| FR.QT.05 | Check client credit and Volcker inventory limits before quoting | Must | P2 | Quote blocked when either check fails |
| FR.QT.06 | Record every RFQ, quote, win, loss and cover for TCA and compliance | Must | P2 | Complete RFQ history queryable per client |

**Central risk book (CRB)**

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.CRB.01 | Aggregate client trades, strategy positions and hedges into DV01 by curve bucket in real time | Must | P2 | Bucket totals equal the sum of underlying positions at all times |
| FR.CRB.02 | Apply a hedge policy per bucket: internalize up to a threshold, hedge beyond it | Must | P2 | Policy changes require approval and are audited |
| FR.CRB.03 | Send hedge parent orders to the execution layer with urgency set by the policy | Must | P2 | Every hedge order links back to the triggering risk change |
| FR.CRB.04 | Publish inventory to the quoting engine for skew | Must | P2 | Skew updates within one inventory event |
| FR.CRB.05 | Report risk and PnL attribution by source (client, strategy, hedge) | Should | P2 | Daily attribution report sums to total PnL |

## 12. Post trade, clearing and regulatory reporting (POST, REG), phase 3

Every fill must flow to booking, clearing and regulatory reporting automatically and idempotently, so a replayed event never creates a duplicate booking or report.

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.POST.01 | Publish every fill as a trade event to Kafka with a stable trade ID | Must | P1 | Event count equals OMS fill count daily |
| FR.POST.02 | Book trades into the books of record with book, desk and legal entity | Must | P3 | Booking break report empty on a normal day |
| FR.POST.03 | Submit eligible Treasury cash trades to a covered clearing agency in line with the SEC Treasury clearing rule (cash compliance date Dec 31, 2026) | Must | P3 | Every eligible trade has a clearing status |
| FR.POST.04 | Track futures and swap clearing status from CME and LCH confirmations | Must | P3 | Unconfirmed trades alerted by end of day |
| FR.POST.05 | Reprocess safely: duplicate trade events are ignored by trade ID | Must | P3 | Replaying a day creates zero new bookings |
| FR.REG.01 | Produce FINRA TRACE reports for Treasury trades within the required window | Must | P3 | Late report rate tracked and alerted |
| FR.REG.02 | Report swap trades to a swap data repository under CFTC rules | Must | P3 | Every swap has an acknowledged report |
| FR.REG.03 | Compute daily Volcker market making metrics (inventory, aging, turnover) per desk | Must | P3 | Metrics produced every business day |
| FR.REG.04 | Keep an immutable audit trail of orders, quotes, limit changes and user actions | Must | P1 | Audit records cannot be edited or deleted by any role |
| FR.REG.05 | Run surveillance for spoofing, layering and wash trades; raise alerts to compliance | Must | P2 | Seeded test patterns all raise alerts |

Sources for the clearing date: [SIFMA Treasury clearing](https://www.sifma.org/explore-issues/treasury-clearing).

## 13. Operations console, monitoring and administration (OPS)

Operators must be able to see the health of every component and intervene in seconds, without any console action touching the hot path directly.

| ID | Requirement | Priority | Phase | Acceptance criteria |
| --- | --- | --- | --- | --- |
| FR.OPS.01 | Web console (React, TypeScript) showing strategies, positions, PnL, working orders and limit usage | Must | P1 | Data refreshes at least every second |
| FR.OPS.02 | Controls for start, pause, stop, parameter change and kill switch, enforced by role | Must | P1 | Unauthorized actions are blocked and audited |
| FR.OPS.03 | Latency dashboard: p50, p99, p99.9 per pipeline stage from HdrHistogram | Must | P1 | Values published every second to Prometheus |
| FR.OPS.04 | Health checks for every process: heartbeat, feed status, session status, GC count, queue depth | Must | P1 | Missing heartbeat alerts within 2 seconds |
| FR.OPS.05 | Alert routing by severity to on call staff | Must | P1 | Critical alerts reach on call within 1 minute |
| FR.OPS.06 | Start of day checklist automated: reference data loaded, limits loaded, sessions up, JIT warmup done | Must | P1 | Trading enabled only after all checks pass |
| FR.OPS.07 | End of day process: cancel residual orders, reconcile positions, roll recordings | Must | P1 | EOD report produced automatically |
| FR.OPS.08 | Reference data management for instruments, calendars, conversion factors and venue rules | Must | P1 | Changes versioned with four eyes approval |
| FR.OPS.09 | User and role administration integrated with corporate SSO | Should | P2 | Access revoked in SSO is revoked in the platform within 5 minutes |

## 14. Nonfunctional requirements (NFR)

Performance targets are design targets to be confirmed in the Phase 1 benchmark; every one is measured automatically and gates the release.

| ID | Area | Requirement | Measurement |
| --- | --- | --- | --- |
| NFR.PERF.01 | Latency | Wire to wire under 10 µs p99 and under 20 µs p99.9 for the T0 path | NIC hardware timestamps over a full replayed peak day |
| NFR.PERF.02 | Latency | Client RFQ response under 1 ms p99 internal processing | Timestamps at RFQ receive and quote send |
| NFR.PERF.03 | Throughput | Sustain 5x the peak message rate observed in the last year (for example FOMC days) without queue growth | Replay at 5x speed in the perf lab |
| NFR.PERF.04 | Jitter | Zero GC pauses during market hours | GC log shows no collections in session |
| NFR.AVAIL.01 | Availability | 99.95% availability during trading hours for T0 and T1 | Monthly uptime report |
| NFR.AVAIL.02 | Failover | OMS and risk failover under 1 second with no lost or duplicated orders | Chaos test each release |
| NFR.REC.01 | Recovery | Full state rebuild from snapshot plus archive in under 5 minutes | DR drill quarterly |
| NFR.DR.01 | Disaster recovery | Secondary site can trade within 30 minutes of primary loss | DR drill twice a year |
| NFR.SEC.01 | Security | All console and API access authenticated via SSO, authorized by role | Penetration test yearly |
| NFR.SEC.02 | Security | Secrets in a vault; no credentials in code or config files | CI secret scan blocks merges |
| NFR.AUD.01 | Auditability | Every order, quote and parameter change traceable to a user or strategy version | Random audit sample passes 100% |
| NFR.MAINT.01 | Maintainability | Hot path modules have 90%+ line coverage and JMH benchmarks per critical method | CI report |
| NFR.MAINT.02 | Determinism | Any production session can be replayed to identical strategy output | Weekly automated replay check |

## 15. Core data entities

These entities define the shared vocabulary; the Interface Control Document will turn each into exact SBE and Protobuf schemas with field types and sizes.

| Entity | Key fields | Owner | Notes |
| --- | --- | --- | --- |
| Instrument | instrumentId, symbol, type (future, cash, swap), tick size, conversion factor, maturity, venue IDs | Reference data | Versioned daily |
| BookUpdate | instrumentId, side, price (ticks), quantity, level or orderId, sequence, rxTime | Feed handler | MBO or MBP |
| FairValue | instrumentId, price, DV01, confidence, input sequence, time | Pricing | One stream for all consumers |
| Signal | strategyId, instrumentId, direction, expected alpha (ticks), half life | Strategy | Logged for TCA |
| ParentOrder | parentId, strategyId, side, quantity or DV01, urgency, limit, end time, algo | Execution | Linked to child orders |
| Order | orderId, parentId, venue, instrument, side, price, quantity, type, state, timestamps | OMS | State machine in section 7 |
| Fill | fillId, orderId, price, quantity, venue trade ID, liquidity flag, time | OMS | Source for positions and TCA |
| Position | book, strategy, instrument, quantity, DV01, average price, realized and unrealized PnL | OMS / CRB | Replicated in Aeron Cluster |
| Limit | scope (instrument, bucket, strategy, desk), type, value, approver, effective time | Risk | Four eyes changes |
| RFQ / Quote | rfqId, client, instrument, side, size, quote price, breakdown, outcome, cover | Quoting | Phase 2 |
| AuditEvent | actor, action, before, after, time, reason | All | Immutable |

## 16. Delivery phases

Phase 1 proves the hot path end to end on one venue; later phases add breadth only once the core meets its latency and fill accuracy gates.

&#91;embedded content: delivery roadmap · 3 phases, 2 gates\]

Each gate's criteria map to requirement IDs above, so a gate passes only when those tests pass.

## 17. Assumptions, constraints and open questions

The items below must be resolved before the Phase 1 build starts, because each changes scope or cost.

**Assumptions**

- Colocation space and cross connects at CME's data center are available for Phase 1.
- The firm has CME clearing membership or a clearing broker relationship for futures.
- Historical MBO data for CME Treasury futures can be sourced for backtesting before live recording builds up.
- Kernel bypass NICs are approved by infrastructure and security.

**Constraints**

- All strategy and model changes go through model risk validation before live use.
- Hot path code must stay within the technology constraints in section 3.

**Open questions**

- [ ] Which Treasury cash venues are in Phase 2 scope, and which offer MBO data?
- [ ] Is Azul Platform Prime licensed, or is OpenJDK with ZGC the production JVM?
- [ ] What is the regulatory retention period the firm applies to raw market data recordings?
- [ ] Which booking system and books of record does Phase 3 integrate with?
- [ ] Who owns the hardware kill switch and its test schedule?
- [ ] What overnight window is available for backtest and calibration jobs?
