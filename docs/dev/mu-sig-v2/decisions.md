# MuSig V2 decisions

Start with the [architecture overview](architecture-overview.md). The [processing rules](processing-rules.md)
are the authority for current behavior; [TODO](todo.md) contains open work. This file records the reasons for
the design and deliberate differences from V1, without repeating the scenario rules.

## Current scope

V2 is an isolated experiment with four execution roles, mediation and two decision flows, protobuf/file
persistence, per-trade lanes, network adapters and mutable read models. Generic lane and processor code has
replaced the earlier direct-runtime implementation. The application still selects V1; full lifecycle and UI
integration is unfinished. The V2 store format has not shipped, and older experimental schemas are not migrated.

## Decisions and rationale

| Decision | Reason and status |
| --- | --- |
| Keep V1 unchanged while V2 is experimental. | V2 receives its backend client and application type directly. Its own setup manages client startup and shutdown; the shared `ServiceProvider` and V1 lifecycle stay as they are in main. Application wiring must select one implementation when V2 replaces V1. |
| Separate a serial lane from a synchronous processor. | Implemented. Infrastructure owns ordering, saves and delivery; protocol definitions own business transitions. The generic processor also supports simple entities. |
| Keep backend operations synchronous inside handlers. | Agreed prototype boundary. It avoids extra asynchronous business states, but occupies a shared worker and leaves a crash window before saving the result. Backend idempotence is assumed, not verified. |
| Keep state separate from cumulative artifacts. | Implemented. Typed states express position; execution data retains signatures and transactions across stages without forwarding them through every state record. Optional data still needs runtime validation. |
| Give every flow its own state and data. | Implemented. Parent writers preserve children. Cross-flow guards read current views; mutations communicate through raised events. |
| Use typed transition definitions with runtime admission. | Implemented. The compiler checks source/event/handler/target compatibility, not business correctness, required optional data or what the current UI may invoke. Shared source/event/target types also cannot distinguish role-specific handler behavior. No additional action-type API is planned now. |
| Model trade lifecycle as a parent. | Implemented. Execution closure and archiving are distinct. Failure propagation is a domain rule declared by the parent, not an assumption that execution is the infrastructure owner. |
| Save each flow step before its reactions. | Implemented. Restart can resume a saved acceptance without repeating it. A later signing failure does not undo acceptance. This deliberately gives up atomicity across the whole cascade. |
| Save outgoing work with the transition. | Implemented. Retry delivers the original message without repeating signing or undoing local closure. Immediate send failure is coordination failure, not domain failure. |
| Deduplicate successful input types per flow. | Matches the chosen V1-style linear protocol policy. Pending copies are not collapsed, and ignored inputs do not consume their type. Repeatable operations need a future scope decision. |
| Keep a mutable read model separate from persistence. | Implemented using existing observables. Stable object identity supports UI bindings; the store updates it and startup reconstructs it. |
| Preserve resolved take-offer terms on `InitializeTrade`. | Agreed. The application resolves participants, mediator, arbitrator and price; lane admission rechecks trading restrictions without silently selecting different terms. |
| Keep optional work best effort. | Logs, contact notification and offer-removal failures do not stop trading. Required setup, timer and observation startup retain their stricter coordination rules. |
| Use existing protobuf patterns. | Implemented with `Proto` / `PersistableProto`, explicit restoration and resolver registration. No separate MuSig event codec or Java serialization hierarchy was added. |

## Deliberate V1 differences

| Area | V2 choice |
| --- | --- |
| Immediate send exceptions | Preserve the saved transition and pending message in all states. V1 can fail the trade after the backend operation succeeds but the send call throws. |
| Duplicate scope | V2 filters every successfully handled type per flow, including same-state reactions. V1 excludes `.stay()` transitions from its processed history and also relies on handler-specific duplicate checks. |
| Caller reporting | Futures can report rejection or unfinished coordination separately from observable trade status. V1 primarily exposes protocol/model state. |
| Recovery | Restore all owners before queueing recovery; preserve per-trade ordering and isolate safely saved domain failures. |
| Mediation request | Local requests require the deposit publication/receipt milestone. Incoming mediator notifications have separate eligibility. |
| Mediation decisions | Separate terminal decision flows. The first successfully handled peer decision wins in arrival order; V1's buffered settlement handling replays rejections before acceptances. |
| Custom finalization | One execution transition saves the peer PSBT on success. Unlike V1, a terminal finalization failure does not preserve that PSBT in execution data. |
| Payment-details requests | One successful response per mediation flow, including after restart. V1 has no equivalent persisted once-per-type history for this request. |
| Timers and force closure | Persist absolute deadlines. Buyer timeout is a placeholder reaction, not implemented force closure; no automatic mediated closure after 24 hours. See [closure comparison](closure-comparison.md). |

## Limits we have accepted for now

Unknown-trade waiting messages and inbound queues are memory-only. Network handoff is not a confirmed durable
transfer or peer acknowledgement. There is no end-to-end exactly-once guarantee or automatic background retry
for failed checkpoints. Best-effort logs are not replayed. The service and router are currently single-use.

The bounded worker pool can permanently close a lane on saturation, and waiting work/rejected IDs have no memory
bound. These are **open risks**, not intended guarantees; see [risks](risks.md). Fixed-pool alternatives and capacity
policies are proposals. The [TODO](todo.md) also tracks arbitration, payout recovery, reliable delivery and integration.

## Review and verification entry points

Start code review at [MuSigTradeService](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigTradeService.java),
[MuSigTradeProcessor](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/MuSigTradeProcessor.java) and [MuSigProtocol](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/MuSigProtocol.java). Generic ownership lives in
[ProcessingLane](../../../common/src/main/java/bisq/common/processing/ProcessingLane.java).

Scenario coverage is in [generic processing tests](../../../common/src/test/java/bisq/common/processing),
[generic state-machine tests](../../../common/src/test/java/bisq/common/statemachine) and
[MuSig V2 tests](../../../trade/src/test/java/bisq/trade/mu_sig_v2). Test presence is not evidence of live backend
idempotence or network durability; those remain explicit follow-ups.
