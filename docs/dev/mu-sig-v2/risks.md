# Processing lane risks

Start with the [architecture overview](architecture-overview.md). This document records open risks and review decisions. The changes described as intended behavior
are not implemented. Follow-up work remains in [TODO.md](todo.md).

## Overview

| Risk | Current behavior and impact |
| --- | --- |
| **R1 — High: temporary worker-pool overload closes lanes** | A rejected worker-start request permanently closes the lane within its current router. The router retains it, so subsequent inputs cannot be processed even after capacity becomes available. |
| **R2 — Unlimited waiting lanes and retained inputs** | Supported messages for unknown trade IDs can create waiting lanes before trade admission. Neither the number of waiting lanes nor their retained inputs has a limit or expiry. Continued traffic can exhaust memory; the bounded worker pool does not bound these collections. |
| **R3 — Unlimited rejected-ID registry** | Each distinct final initialization rejection adds an ID retained until application restart. MuSig verifies requests before recording final rejections, but the set still has no bound. Evicting IDs would allow previously rejected requests to run admission again. |

R1 has been reviewed below. R2 and R3 still need their own policy discussions; no limits or eviction rules
have been selected.

## R1: Temporary worker-pool overload

### Concrete example

1. Trade A's lane is idle and receives a message.
2. The message enters A's input queue.
3. The lane asks the shared executor to run its queue worker.
4. All workers are busy and the executor's waiting queue is full, so the executor rejects the request.
5. The lane calls `abort`, closes itself, clears its queued submissions and fails their futures.
6. The router keeps the closed lane. Later messages reach it and fail, even when executor capacity has returned.

The executor's queue holds lane-worker tasks, not individual trade messages. Once scheduled, a worker drains
its lane's input queue. This failure affects a lane whose worker cannot be scheduled; it does not interrupt
other lanes that are already running.

### Why this matters

A temporary lack of worker capacity becomes a lasting loss of processing for that lane. A traffic burst or
recovery of many saved trades can trigger it without any business-rule or handler failure.

The persisted trade is not automatically marked failed. Its last saved checkpoint and pending work remain
available for restoration, but the closed lane cannot resume them. Newly queued inputs that have not reached
a checkpoint are cleared from memory; their failed futures do not guarantee network redelivery.

### Agreed direction, not yet implemented

Temporary saturation should delay processing:

- Keep the lane and its queued inputs, preserving their order.
- Keep submission futures pending while waiting for a worker.
- Allow a waiting worker to start when capacity becomes available, without concurrent workers for one lane.
  Whether this uses executor queueing or an explicit startup retry remains under review.
- Perform normal command admission when each input gets its turn.

This adds no durability: queued inputs remain in memory until checkpointed. Memory limits remain a separate
concern.

A permanently stopped executor is different from temporary saturation. If processing cannot resume, waiting
submissions must fail explicitly. Normal shutdown must still drain accepted work rather than leave futures
pending indefinitely.

### Remaining implementation review

First reproduce pool saturation with a focused scenario. Compare waiting in an executor queue with explicit
worker-start retry, and decide capacity limits and shutdown behavior before selecting an implementation. Keep this in generic lane scheduling; a scheduling retry is not a
persisted business timer. Verify input retention, ordering, single-worker ownership and future completion.

### Code references

- [MuSigTradeService](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigTradeService.java): shared bounded executor, currently two core workers, five maximum
  workers and a queue capacity of 100 tasks.
- [ProcessingLane](../../../common/src/main/java/bisq/common/processing/ProcessingLane.java):
  `enqueue` catches `RejectedExecutionException` and calls `abort`; `abort` closes the lane and clears queued work.
- [LaneRouter](../../../common/src/main/java/bisq/common/processing/LaneRouter.java):
  `findOrCreateLane` returns the existing lane; executor rejection does not remove it.
- [RejectedEntityRegistry](../../../common/src/main/java/bisq/common/processing/RejectedEntityRegistry.java):
  the unbounded set discussed in R3, separate from worker-pool rejection.

The review below is preserved as written. Its README section numbers refer to the earlier document; the current
pool walkthrough is in [threading](processing-lane-threading.md#3-the-shared-executor-decides-when-that-worker-runs).
The fixed-pool alternative has not been selected or implemented.

## comments_1

### Concern about the R1 direction

Retrying worker startup adds a new scheduling mechanism to the lane:

- `ThreadPoolExecutor` has no "capacity is available again" signal. A retry needs its own trigger, either a
  timer with backoff (too late or too early) or a hook on every finishing worker (couples all lanes).
- The new state needs its own rules: at most one pending retry per lane, interaction with shutdown, and the
  distinction between temporary saturation and a permanently stopped executor.
- It needs dedicated tests in `ProcessingLane`, already the most complex class (929 lines).

### Facts about the current pool

- `boundedCachedPool(2 core, 5 max, queue 100)` rejects only when five workers are busy and 100 tasks wait.
- A queued task is a lane worker, not a message. The `draining` flag allows at most one per lane, so the
  executor queue can never hold more tasks than there are lanes.
- Workers above the core size start only when the queue is full (see README section 5). Normally the pool
  runs with two workers; workers three to five appear only during overload.

### Simpler alternative

Use a fixed pool with an unbounded queue, for example `Executors.newFixedThreadPool(n)` or an
`ExecutorFactory` variant with an unbounded `LinkedBlockingQueue`.

- While running, the executor never rejects. A lane whose worker cannot start yet waits in the queue. This
  provides every point of the agreed direction: the lane and its input order are kept, futures stay pending,
  one worker per lane, and normal admission when each input gets its turn.
- It rejects only after shutdown, which is the "permanently stopped" case. The existing `abort` already fails
  waiting submissions explicitly there, so `ProcessingLane` needs no change.
- Shutdown is unaffected: the router drains accepted work before the service closes the executor.

### Costs and open points

- R1 becomes a memory question: the executor queue grows with the number of lanes, so the R2 bound on
  waiting lanes also bounds the executor queue. R2 needs that bound anyway.
- README section 5 (bounded executor queue) has to be revised.
- Pool size: keep two workers, or use five from the start?

Should we replace the R1 direction with this alternative?
