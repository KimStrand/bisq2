# MuSig V2 input processing

These rules apply to the experimental MuSig V2 protocol. The lane owns serial execution, persistence,
retry scheduling and effect delivery. The processor owns flow selection, validation and transitions.

## New trade initialization

Creating a trade requires an initial snapshot and a first protocol input. Save them together before
setup or handlers run. Process the first input before messages that waited for initialization.
Restoring an existing trade uses its saved checkpoint and does not create a new first input.

## Messages before initialization

- A message whose trade does not exist yet may wait only if its type is declared as able to wait.
  Commands never wait. A waiting message creates no lane and is not saved.
- At most 100 messages wait, for all trades together, in arrival order. When a message arrives while 100
  messages wait, drop the oldest one and fail its submission. Equal messages of the same trade wait as
  separate entries and each counts toward the limit. The network drops exact repeats, and each flow ignores
  an input type that it has already handled.
- The trade's initializer creates its lane. The trade's waiting messages, and its messages that arrive
  meanwhile, follow the initializer in arrival order as ordinary submissions.
- A dropped message is lost: the network has already acknowledged it, and the sender does not send it
  again. The limit protects memory, not the message.
- Log entries about a waiting message name only its type and its sanitized trade ID.

## One input attempt

1. Prevalidate an external message before retaining it. Use the last saved snapshot for this prevalidation.
   Handler payload verification happens later. If that snapshot shows that the message can no longer be handled,
   neither retain nor save it. That is the case when no flow is interested in the message, or when its flow
   has stopped, has already handled its type, or can no longer reach a state which handles it.
   None of these can change back, so handling would only ignore the message, now and later. Its submission still
   finishes earlier unfinished work, like any other submission. Otherwise save the input before executing its
   handlers.
2. Each input type that the processor routes has at most one registered destination flow. This depends on the
   registration only, not on readiness or processing history. A second destination is a programming error: the
   message is still saved, and the lane stops before any handler runs. Flows pass facts to each other as raised
   inputs. A failure report is not routed; it goes directly to the failing flow.
3. Retain the input itself, without a destination identifier. During each attempt, its flow checks history,
   readiness and admission against the latest working snapshot. A successful flow records the concrete input type
   in its own history; later inputs of that type are discarded. An ignored input records nothing.
4. Handle raised events only after the input's result is saved. An exception without a valid failure snapshot
   stops the lane.
5. The result carries the next snapshot, all effects (including logs), the raised inputs and the timer changes.
   If the flow waits for a later state, the outcome is deferred and the input is kept.
6. Save the snapshot, processing history, remaining inputs, raised inputs, persistable effects and absolute
   timer deadlines together. Execute effects only after that save succeeds.
7. Give ready raised inputs priority over older pending inputs. Deferred inputs do not block ready ones.
   Reconsider waiting inputs after processor progress; do not busy-loop over unchanged state.
8. Commands never defer and always complete their input attempt. Ordinary commands wait only for their
   serial turn and are not retained for a future business state. The first initialization command is
   saved with the new trade; raised and scheduled inputs are saved with their producing transition or deadline removal.

## Timers

- A handler starts or replaces a timer with an absolute deadline and the input to deliver, or it cancels a
  timer by its ID. It returns these timer changes separately from its effects. Save the changed deadlines
  together with the handler's result (step 6 above).
- A timer is not an effect. Save it only as its deadline. The live timers follow the saved deadlines: start,
  replace or stop a timer right after the save which changed its deadline, before the effects of that save.
  After a restart, start a timer for every saved deadline. A deadline which has passed is due at once.
- When a timer is due, replace its deadline with its input in one save. Then handle the input like any other.
- A timer which cannot start stops further input handling and the execution of effects until a retry succeeds.

Reason: a timer is an input for later, as a raised input is an input for now. The saved deadline already says
everything which is needed to start the timer. A second saved entry as an effect would repeat it, would cost
one more save for each timer start and cancel, and would need a rule for the case in which both entries disagree.

## Trade restrictions

A trade restriction is a halt of trading, or a minimum version for trading which the node does not have (rules 1
and 2 of the [trade restrictions](../trade-restrictions.md)). It exists to stop the handlers of the trade protocol
during an emergency.

- Refuse an input which arrives during a restriction, and do not keep it: a message of the peer, the command
  which starts a trade, and the commands for the payment and for accepting a mediation result.
- Do not create a trade during a restriction. The taker's node and the maker's node check the restriction again
  when the lane creates the trade, because a restriction can arrive while the request waits for its turn. The
  refusal of the maker is not final: the same request can start the trade later.
- Do not start the handler of an input which the lane keeps for the trade protocol. That is a saved message of
  the peer, and every saved reaction to a restricted input: the first step of the taker, the input of a due close
  timer, and the signing and the finalization of the payout after an accepted mediation result. Pause such an
  input: keep it pending and saved, and handle the other inputs of the trade. This holds in the running node and
  after a restart.
- When the alerts change, let every trade continue with its paused inputs which are allowed now, in their saved
  order. A later input of the trade and a restart also continue with them.
- Never pause a command of the user. The lane does not keep a command (step 8 of "One input attempt"), so the
  node checks the restriction only when the command arrives.
- Mediation (the request, the rejection of a result, the messages of the mediator), archiving, the deposit
  confirmation and the handling of failures stay available during a restriction.

Reason: a restriction must also stop an input which arrived, or became due, before the restriction. The lane
saves an input before it handles it, and it saves the reaction to an input as an input of its own. So an input
can wait across a restart, for another input or for a deadline, and become ready during a restriction. Without
the pause, the node would handle it then, although it would refuse the same input if it arrived at that moment.
The node does not drop a paused input, because the peer does not send a message again and a deadline does not
occur again.

## Failure and retry

- A failed save keeps the computed checkpoint in memory. Retry that save without rerunning handlers.
- A failed message handoff keeps the original message and blocks later sends within the trade, across
  recipients and flows. Other local effects and eligible incoming inputs may continue.
- A failed required local effect, such as starting deposit observation, stops further input handling.
  Continue prevalidating and saving new incoming messages while waiting for that effect to succeed.
- A failed optional local effect, such as switching the trade chat to mediation mode, is logged and
  dropped. It does not stop input handling, and the lane does not retry it. The trade must not stop
  because its chat cannot enter mediation mode.
- Retry failed saves, setup and unfinished effects automatically after a delay, through the same serial
  lane. Keep at most one scheduled retry per lane. Cancel obsolete retries after success or shutdown.
  Restart reconstructs recovery from persisted work; the live retry timer is not a business deadline.
- Save the removal of each completed effect before continuing. If that save fails, a live retry does
  not repeat the effect. A crash can restore the older saved effect and execute it again.
- Logs are best-effort effects and are not persisted. After the producing checkpoint succeeds, attempt
  them after the persistable effects, even when an effect or its removal checkpoint fails. A failed log
  must not prevent later logs or replace the original failure. Do not retry logs or replay them after restart.
- Backend work inside handlers is not atomic with checkpoint persistence. A crash before the result
  is saved can repeat backend work. These rules do not establish exactly-once execution or peer delivery.

## Serial execution and shutdown

- Each trade has one serial owner for input processing, recovery and scheduled work. Reentrant submissions
  wait for a later turn; they do not interrupt the current input attempt.
- Shutdown stops admission and drains accepted tasks unless processing aborts. Inputs still waiting for
  initialization then fail explicitly. Saved pending work and deadlines remain available for restoration.
- A temporary lack of free workers delays processing. It must not reject inputs or close the lane. Waiting
  inputs keep their order, and their submission futures stay pending until the lane processes them.
- A worker that can never start, because the executor has stopped or thread creation fails, closes the lane
  and fails queued submissions with the original cause. Queued work and shutdown must not remain pending
  indefinitely because the worker could not start.
- An unexpected failure in task finalization aborts the lane and rejects queued tasks instead of leaving
  them waiting indefinitely. The lane resolves its outstanding submission futures; already completed results
  do not change. This does not mark the persisted trade failed or discard its saved checkpoint.
- Stop cleanup runs exactly once. Cleanup failure is logged even when nobody is observing shutdown and
  completes shutdown exceptionally. Cancelling one caller's shutdown future does not cancel processing,
  cleanup or other callers' observation of shutdown.

## Startup publication

- Rebuild every saved trade's read model before starting recovery or completing service initialization.
  A trade must remain visible even when no worker has run or its recovery is blocked.
- Register and validate all restored trades before publishing them. Initial publication runs on the
  startup thread before any recovery is queued; later publication runs under the trade's serial owner.
- Inputs submitted by publication observers remain buffered until recovery is queued for every restored
  trade. Observer failures must not prevent publication of other trades or their recovery. Observers
  must not wait for initialization or submitted work from inside publication.
- Shutdown during publication stops remaining startup work and fails buffered submissions. A publication
  already in progress may finish. Startup does not wait for recovery to complete.
- The result of recovery is available separately from initialization. A failed recovery is logged even
  when nobody observes that result. A recovery which a shutdown ended is not logged as a failure.

## Compatibility

The newest local release tag checked for this change is `v2.1.15`. It contains neither the V2 trade
record nor the addressed-input format. V2 pending inputs now store `common.Event` directly. There is
no migration for unreleased prototype data. Network message formats and MuSig V1 remain unchanged.

See the [architecture overview](../../../dev/mu-sig-v2/architecture-overview.md) for implementation entry points.
