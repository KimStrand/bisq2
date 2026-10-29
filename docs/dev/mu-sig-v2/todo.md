# MuSig V2 open work

Start with the [architecture overview](architecture-overview.md). Current behavior belongs in
[processing rules](processing-rules.md); this file contains only unresolved decisions and follow-up work.
The items below preserve the 27 open items from the earlier TODO. None authorizes an unagreed behavior change.

## Execution and disputes

- [ ] **Arbitration after mediation failure.** Define how a trader starts arbitration after an invalid mediator
  closing result fails the case. V1 expects a closed case and valid result/signature, which this failure may not
  provide. Decide the evidence to retain (including the failed notification), admission and initiation path.
  Do not silently discard the invalid result or automatically escalate without that decision.

- [ ] **Mediation or decision failure after custom signing.** A failed case/child can stop the path that would
  deliver peer payout data to execution. Decide whether the parent escalates failure or execution needs an
  independent settlement input. Failed flows must not resume processing as a workaround. Existing exceptional
  proposal-hashing coverage demonstrates the possibility; a normal business scenario has not been established.

- [ ] **Make payout mismatches explicit.** Validation is spread across context views, peer verification,
  execution readiness and finalization. A peer decision can be accepted before we know whether payout IDs match.
  Decide how to represent blocked finalization, which artifacts to retain and what recovery means. Preserve the
  current known-ID ignore path and peer-first retained event until agreed otherwise. Terminal finalization failure
  currently discards the peer PSBT; changing that retention is a separate decision.

- [ ] **Recover a failed deposit observation stream.** Current stream callbacks log and continue, so confirmation
  may be missed until restart. Explore a lane-queued operational notification that rechecks current recovery
  declarations and saves a new observation effect without a business-state transition or once-per-type suppression.
  Decide trigger/backoff and health reporting first. Avoid duplicate subscriptions and reconnect loops; reconcile
  continuation after stream error with pausing on a required start effect. Cover stale callbacks, save failures,
  restart and an error delivered synchronously while the start call returns normally.

- [ ] **Complete the four-role closure review.** Use [closure comparison](closure-comparison.md). Remaining
  decisions are buyer recovery and seller recovery after custom signing, including late messages, timer races and
  restart. There is no agreed automatic mediated force closure after 24 hours.

- [ ] **Implement buyer recovery.** The persisted 24-hour timeout currently logs and stays in `PaymentInitiated`;
  it consumes its input type and removes the deadline. Define how to obtain and validate the seller's signed normal
  swap transaction/deposit spend and confirmation before introducing successful forced closure. The inspected backend
  lacks the required observation path. Cover normal and mediated cases, timer start/cancel/restart, failures and the
  backend/save crash boundary. Also decide what to do with older trades that already consumed the placeholder timeout
  and therefore have no remaining deadline.

- [ ] **Define seller recovery after custom signing.** The pinned backend rejects ordinary seller force closure
  from that state. Agree a compatible recovery mechanism, trigger and timing before adding transitions or automation.
  Do not infer support from the existing seller normal-settlement timeout.

## Scheduling and capacity

- [ ] **Centralize executor configuration if useful.** Review whether the chosen lane pool policy belongs in
  `ExecutorFactory`. Existing fixed-pool utilities are an alternative; do not add configuration machinery before
  selecting overload/capacity behavior. This is separate from changing the pool itself.

- [ ] **Consider separating serial queueing from coordination.** Revisit only if `ProcessingLane` responsibilities
  are hard to follow; its line count alone does not justify another abstraction. Preserve the one-worker invariant,
  processing order and explicit save/effect boundaries. Deferred from review lane 3, section 3 point 4.

- [ ] **Handle temporary worker-pool overload.** Current executor rejection permanently aborts a lane, even for
  an existing trade. The agreed objective is delayed ordered processing with retained inputs and pending futures,
  not permanent closure. Worker-start retry and a fixed pool with an unbounded task queue are alternatives still
  under review; the latter shifts the risk toward memory. Decide capacity admission before claiming the problem
  solved. Reproduce five busy workers plus 100 queued lane tasks; cover single-worker ownership, eventual progress,
  permanent executor shutdown and draining. See [R1 and its review comments](risks.md#r1-temporary-worker-pool-overload).

- [ ] **Bound waiting lanes and retained work.** Agree limits on unknown IDs, inputs/bytes per ID and overall work,
  plus overflow/expiry and completion of waiting futures. Also account for initialized lanes' inbound queues,
  startup buffering and any replacement executor queue. These collections are currently unbounded; the worker-pool
  bound does not protect them. Preserve early-message support unless explicitly revising its policy. See R2 in
  [risks](risks.md).

- [ ] **Bound final initialization-rejection memory.** The concurrent registry grows for the application session;
  one verified identity can produce many IDs. Eviction permits old rejected requests to initialize again, so choose
  an explicit overflow/replay policy before implementing a cap. This is distinct from waiting-lane cleanup and must
  survive future router replacement. See R3 in [risks](risks.md).

## Delivery and persistence

- [ ] **Verify backend idempotence after a crash.** We currently assume operations can safely repeat after backend
  success but before checkpoint success. Verify this for setup, signing, publication and normal/custom closure using
  stable trade/operation inputs. Live save retry does not repeat a handler, but restart can. Add backend-supported
  recovery or operation identity only where evidence requires it; mock persistence tests cannot establish this guarantee.

- [ ] **Measure checkpoint cost before batching.** Each changed save clones and rewrites the growing trade store,
  waits on a writer shared with other stores and holds the persistence lock. Measure realistic trade counts and
  transition/effect cascades. Any optimization must preserve candidate isolation, per-step recovery and effect order.

- [ ] **Consider automatic failed-checkpoint retry.** Currently a later eligible input or recovery must retry.
  A possible design schedules at most one live retry per lane, enqueues it on the lane worker and retries only the
  calculated save. Increasing capped delays (for example 5/10/20/30 seconds) are a proposal, not agreed policy.
  Reset/cancel on success and shutdown; handle stale callbacks, a later input winning the retry and worker rejection.
  Do not rerun handlers/effects or create a persisted business timer. Keep the original submission result unchanged
  and publish cleared persistence errors on success. Outgoing-message retry is a separate scope.

- [ ] **Decide whether local reactions should precede outgoing delivery.** Current order is save one transition,
  dispatch effects/logs, then process ready raised events. Explore saving each ready local reaction before dispatching
  accumulated effects. Preserve every checkpoint, allow deferred work without blocking required sends, and retain
  the custom-payout message even if later finalization fails. Review timer/chat/log dependencies and live/restart
  consistency. Keep current behavior until this is resolved.

- [ ] **Decide whether logs need reliable delivery.** Logs are best effort, absent from pending work and not
  rebuilt after restart. Specify which entries need delivery, their IDs/payloads, completion and duplicate behavior
  before adding persistence. Include mediator logs arriving before channel creation: that buffer is memory-only.
  Keep log delivery separate from business protocol progress unless explicitly agreed.

- [ ] **Systematic restart coverage at saved checkpoints.** Before replacing V1, exercise each role and mediation
  ordering with a controlled clock, fake backend and two-party delivery, restoring after each meaningful saved step.
  Assert state, processing history, pending input/effect order and deadlines, including already-handled duplicates.
  This supplements focused tests; it cannot prove backend idempotence in the unsaved crash window. Deferred from
  review lane 3, section 3 point 6.

- [ ] **Fresh payment-details response after successful handling.** Decide whether a mediator needs another response
  after the once-per-flow response has succeeded. First establish a concrete recovery use case; response-send retry
  is different from processing a fresh request. V1 has no persisted once-per-type history here but filters equal
  network payloads in memory. Do not add generic repeatable-event or network changes without that decision.

- [ ] **Verify live outgoing-adapter behavior.** Test immediate exceptions versus returned failed futures, offline
  peers, mailbox/retry paths and a throw after network work has already begun. Ensure overlapping retries keep the
  saved ID/payload/recipient. Current service-pair tests use a mocked network and do not prove transport durability.

- [ ] **Close the network handoff durability gap if required.** A normal send return permits lane removal before
  the network retry record is durably saved; rate limiting, skipped writes or save failure can leave neither copy
  after a crash. Define a durable handoff acknowledgement before changing either layer. Cover concurrent retries,
  successful delivery, failed network save and crashes around lane removal with the same original message.
  The gap is accepted for now; no network-layer change is authorized by this TODO.

## Lifecycle, chat and public API

- [ ] **Define recovery after failed shutdown.** V2 retains its client when trade cleanup fails.
  V2 is single-use and caches its shutdown result, so it cannot currently retry a failed shutdown. Before automated
  recovery, define idempotent resource/backend cleanup. Add a V1 activation regression for the shared ownership
  guard before merging: V1 can recover through its awkward on/fail/off/retry/on sequence. Review lane 3 finding 5
  is low severity for today's exit-only V2 use, but matters for a settings toggle.

- [ ] **Discuss chat-channel closure.** Decide whether execution completion, archiving or an explicit user action
  closes chat, and what remains readable/replyable for traders and mediator. Cover late case updates, pending logs,
  history UI and restart. Keep chat closure separate from preserving saved outgoing protocol messages. No channel
  closure policy has been implemented by the archive API.

- [ ] **Integrate V2 with application lifecycle, routing and UI.** Before replacing V1, support its settings toggle:
  retain the service's store/read-model/rejected-ID memory, create fresh activation resources/router and keep old
  callbacks targeting the old closed router. Do not enable a new activation until lanes and backend shutdown are
  confirmed. Test duplicate listeners, cache replay, existing trades, old finally rejected requests, messages received
  while disabled, stable read models and fresh-attempt IDs. Finish arbitration routing and its payment-details path;
  avoid requiring a live V1 service merely for maker agent-selection helpers. Wire read-model preparation/error fields
  and missing trade/mediation fields into the UI, then decide any manual retry controls. Current maker/taker setup,
  chat, contact, incoming mediator and outgoing-adapter work need not be implemented again.

- [ ] **Review service operation results for callers.** Public futures currently collapse pending coordination and
  domain rejection/failure into exceptional completion. Decide whether callers need an explicit applied-but-pending
  result or observable outgoing-work status before changing the API. Never encourage resubmission of an already-applied
  command. There is no public manual pending-work retry API now; any future retry must resume coordination. Preserve
  original rejection exception types and distinguish setup/persistence errors.

## Branch preparation

- [ ] **Plan commit and PR boundaries.** Separate generic processing/state-machine infrastructure from MuSig V2
  integration at stable, reviewable boundaries. Do this when preparing the branch; do not fragment the prototype
  prematurely. Deferred from review lane 3, section 3 point 7.

- [ ] **Separate shared persistence fixes.** Give strict-read/write failure handling and related shared persistence
  changes their own commit with regression coverage, rather than burying them in the V2 protocol change.
