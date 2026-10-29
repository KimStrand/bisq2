# Processing lane threading

Start with the [architecture overview](architecture-overview.md). Current persistence and failure rules live in
[processing rules](processing-rules.md); this walkthrough explains queue and worker behavior.

This describes the current implementation, checked on 2026-10-28. MuSig V2 still uses the bounded worker pool.
The fixed-pool alternative is only a proposal. There is no general "we are too busy; refuse new work" check.

## 1. An input arrives at the router

The router checks submission restrictions, finds or creates the appropriate lane, and submits the input.
During startup, it buffers inputs until routing starts. It does not check queue sizes or memory usage.

An unknown trade ID can create a lane only for a registered initialization input or an input type configured
to wait for initialization. That eligibility check does not impose a limit on the number of lanes.

See [LaneRouter](../../../common/src/main/java/bisq/common/processing/LaneRouter.java),
particularly `submit` and `findOrCreateLane`.

## 2. The input enters that lane's queue

| Lane situation | What happens |
| --- | --- |
| Its worker is already running or waiting in the executor queue | Append the input to the lane's queue. No additional executor task is submitted. |
| The lane is idle | Append the input, then ask the executor to run a worker for that lane. |

The `draining` flag covers both a running worker and a worker waiting in the executor queue. Additional inputs
therefore do not each create another executor task. A worker processes its lane's submissions sequentially
until the queue is empty.

See [ProcessingLane](../../../common/src/main/java/bisq/common/processing/ProcessingLane.java),
particularly `enqueue` and `drain`.

## 3. The shared executor decides when that worker runs

[MuSigTradeService](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigTradeService.java) configures:

- Two core workers.
- A waiting queue containing up to **100 lane-worker tasks**.
- Up to five workers; extra workers are created only when that queue fills.
- A 30-second idle timeout for workers above the core size.

**Those 100 tasks are not 100 messages.** One queued worker can represent a lane containing many messages.
Different lanes share the pool; a trade does not have a dedicated thread.

## 4. A worker request can be rejected

If five workers are busy and 100 tasks are waiting, another worker request is rejected.

The lane's rejection handler then:

- Closes that lane.
- Clears its queued submissions and pre-initialization stash.
- Completes their futures exceptionally.
- Stops its live input scheduler.

The router leaves the closed lane registered. Later inputs for that trade therefore fail, even after workers
become available. This can affect an **existing trade**, not just a new trade attempt.

This does not automatically mark the persisted trade as failed or delete its last saved checkpoint.
The closed lane cannot resume processing in the current router. Already saved state and pending work remain
available for restoration.

## 5. Memory is only partially bounded

| Collection | Current limit |
| --- | --- |
| Executor's waiting tasks | 100 |
| Each lane's incoming submissions | None |
| Messages waiting for initialization | None |
| Number of waiting lanes | None |
| Router's startup buffer | None |

An input entering the lane's queue is **not yet persisted**. For an initialized trade, eligible incoming
messages are checkpointed when the worker admits them, before processing. Messages waiting for initialization
remain memory-only. Clearing an unsaved input does not guarantee network redelivery.

Consequently, an already-busy lane can keep accumulating inputs without hitting the executor's 100-task limit.
If incoming work keeps outpacing processing, retained work can exhaust memory despite the bounded executor queue.

The overload and memory-limit discussions are recorded in [risks.md](risks.md). No replacement executor or
capacity policy is implemented by this documentation change.
