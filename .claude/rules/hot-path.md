---
paths:
  - "common/src/main/**"
  - "venue-sim/src/main/**"
  - "feed-handler/src/main/**"
  - "pricing/src/main/**"
  - "strategy-api/src/main/**"
  - "strategies/src/main/**"
  - "execution/src/main/**"
  - "risk/src/main/**"
  - "oms/src/main/**"
  - "gateway/src/main/**"
  - "sim/src/main/**"
---

# Hot path rules

These apply to every per message code path in the modules above (FRS TC.05). Startup and shutdown code may allocate; say so in a comment when it does.

## Allocation

- No allocation per message. No `new`, no autoboxing, no varargs, no `String` building or concatenation, no `String.format`, no lambdas or method references that capture state, no streams, no `Optional`.
- No iterators over JDK collections (`for (x : list)` allocates an iterator for most collections). Use indexed loops over arrays.
- Use Agrona primitive collections (`Long2LongHashMap`, `Int2ObjectHashMap`, `LongArrayList`) instead of `java.util` maps and lists with boxed keys.
- Preallocate and reuse: flyweights, scratch buffers and result holders are fields created once at startup.
- `./gradlew allocationTest` must stay green. Tag new hot path tests with `@Tag("allocation")`.

## Encoding

- Read and write messages with SBE flyweights wrapped over an `UnsafeBuffer`. Never copy a message into a POJO.
- Prices are `long` ticks of the instrument and quantities are `long`. No `double` or `float` for prices or money. Convert at the edges only.

## Concurrency

- Single writer: each piece of state is owned and mutated by exactly one thread.
- No `synchronized`, `Lock`, `BlockingQueue`, `ConcurrentHashMap` or `volatile` read and write pairs used as a substitute for message passing. Threads talk through SBE messages on Aeron streams.
- Watch for false sharing on fields written by different threads; pad or separate them.

## Control flow and time

- No exceptions for control flow. Return status codes; exceptions are for bugs and startup errors only.
- No reflection, no logging that formats strings on the hot path.
- Time comes only from an injected `Clock`. Never call `System.nanoTime`, `System.currentTimeMillis` or `Instant.now` directly (`checkBannedTimeCalls` enforces this).
- No logic that depends on hash map iteration order; output must be deterministic for the same input and seed.
- Any change to a per message method needs a JMH benchmark in `bench`.
