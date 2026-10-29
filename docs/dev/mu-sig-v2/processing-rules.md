# Processing rules

This is the maintained source of current MuSig V2 processing, persistence, duplicate and failure rules.
Start with the [architecture overview](architecture-overview.md) for structure and [decisions](decisions.md) for rationale.
[TODO](todo.md) and [risks](risks.md) contain proposals and unresolved work, not additional current guarantees.

Each rule applies per trade unless stated otherwise. **Saved** means the checkpoint write succeeded.
The checkpoint includes the trade and its unfinished work. Network receipt, queueing and read-model publication
are different milestones; none by itself proves persistence or peer processing.

## Submission and initialization
| Situation | Rule |
| --- | --- |
| Submission policy refuses a new command or incoming message. | Return `Completed(Rejected)` before buffering or creating a lane. Retain nothing, leave the trade unchanged and keep its ID eligible for a later permitted submission. |
| An admitted input waits during startup or before initialization. | Do not repeat router submission checks when releasing it or retrying saved work; processor validation and state rules still apply. |
| A take-offer command reaches taker trade admission after waiting. | Recheck trading restrictions before creating the trade. Reject the attempt if trading is now halted or the minimum-version requirement is no longer met. |
| A message arrives before trade initialization. | Keep it in memory until initialization. It can be lost on shutdown or a crash. |
| Initialization fails before a trade is prepared. | Clear waiting messages for that trade ID, fail their waiting futures and remove the waiting lane. Only an explicit final rejection records the ID. |
| A validated trade attempt is explicitly rejected. | Remember its trade ID in the shared rejection registry before removing the lane. That attempt cannot initialize again during this application session. |
| An input arrives for a remembered rejected ID. | Return `Completed(Rejected)` with “Initialization was already rejected for this entity ID”. Do not create a lane, rerun admission or send another peer rejection report. |
| A temporary condition, such as missing market price, causes an explicit rejection. | That trade ID remains blocked even after conditions improve. A fresh take-offer attempt uses a new timestamp and ID. This is based on the maker's decision, not confirmed receipt of its best-effort rejection report. |
| A request has an unverified sender, signature or claimed trade ID. | Do not remember its claimed ID. A later valid request must still be able to initialize it. |
| A peer is ignored before or during validation. | Drop the current request silently without remembering its trade ID. After un-ignoring the peer, a resend remains eligible. |
| Shutdown rejects initialization before the lane accepts it, or initialization fails without a final rejection decision. | Do not remember the ID as rejected. A later attempt remains eligible. |
| A single-use router is replaced. | Supply the same rejection registry to the new router. The service retains the registry; only routers check and record IDs. Full V2 disable/re-enable support remains deferred. |
| The application restarts with a new registry. | Rejected IDs are forgotten. A replayed request may be evaluated again; rejection history is not persisted. |
| A new trade is prepared. | Save the trade and first protocol input before setup or handlers run. Replay waiting messages after setup and that first input. |
| Unknown input type or unknown non-initializing command/backend callback. | Reject it. Only registered initialization inputs and types configured to wait can create a lane; waiting does not create a trade. |
| Two initialization inputs use the same ID. | Serialize them in the same lane. Once a trade exists, do not rerun maker admission or send an admission rejection for that existing trade. |
| Caller cancels its future. | Accepted work continues. Equal submissions still have independent futures and results. |
| Chat setup fails after the initial save. | Keep the first input saved in `Init`; do not perform backend preparation until setup succeeds. Both roles use this ordering. |
| New-trade contact creation fails. | Log and continue. Attempt it after setup and before the first input, respecting settings/profile checks; do not replay it on recovery. |

The rejection registry is a concurrent set with no cap, expiry or persistence. It grows once per finally rejected
validated ID; one signed identity can generate many IDs. Evicting an entry would permit that attempt again.
The service owns this memory and injects it into each router; full service reactivation is still deferred.

### MuSig admission boundaries

| Check or action | Owner and behavior |
| --- | --- |
| Resolve take-offer terms. | `MuSigService` validates participant bans/rate limits, ignored maker and profile availability; resolves mediator, arbitrator and market price; calculates the timestamp-based attempt ID and creates `InitializeTrade`. Development mode keeps V1's allowance for missing agent selections. |
| Admit a taker on the lane worker. | `MuSigTradeAdmission.admitTaker` rechecks trading restrictions and constructs the contract/trade using the supplied terms. It does not choose new agents or a new price. Validation failures retain their original application exception type. |
| Admit a maker. | Use the shared V1 validator: identity/signature/derived ID, known taker profile, economics, account commitments, payment methods, amount/price/limit constraints, dispute agents and active matching offer/roles. Resolve active local identity and the matching account payload. |
| Restore an initial maker trade. | The first handler revalidates identity and verifies contracts, hashes and peer signature. Saved initialization must not bypass these handler checks. |
| Unsupported protocol version. | Maker admission rejects silently, before the report-producing validation path. New trades use `MuSigProtocol.VERSION`; restored trades keep their saved version. There is no version negotiation. |
| Maker validation rejects a request. | Send the existing rejection report best effort. Unexpected runtime failures within request validation use V1's generic `OFFER_NOT_AVAILABLE` response; do not send stack traces. Ignored peers get no reply. A missing local receiving identity prevents sending. |
| Rejection-report delivery fails. | Keep the original admission failure. No trade/outbox exists for this report, and failure to report does not reopen a finally rejected attempt. This direct send uses V1's path. |
| Emergency trading restrictions. | Submission policy covers initialization, payment initiation/receipt confirmation, development confirmation bypass, local mediation acceptance and trader protocol messages. It runs on the submitting thread before buffering or lane creation. |
| Banned incoming sender. | Submission policy rejects trader and mediator inputs locally, without a peer rejection report or successful-type history. |
| Request or reject mediation during a trading halt. | These remain available subject to domain eligibility. Request admission also checks the local trader's ban status using the saved identity. Acceptance has no additional local-user ban check. |
| Mediator notifications and payment-details requests during a halt. | Remain eligible; sender bans and lane-side trade/assigned-mediator validation still apply. Arbitration messages are not wired through this path. |

Sources: [submission policy](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigSubmissionPolicy.java), [trade admission](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigTradeAdmission.java),
[application service](../../../mu-sig/src/main/java/bisq/mu_sig_v2/MuSigService.java) and
[shared request validator](../../../trade/src/main/java/bisq/trade/mu_sig/messages/network/handler/maker/MuSigTakeOfferRequestValidator.java).

## Input retention and duplicate handling

`StateMachineDefinition.canHandle` checks readiness for all inputs; `admit` adds command-only conditions.
Handlers keep payload verification. Explicit `ignoreInput()` is distinct from a verification exception.
`shouldDiscard` can supplement reachability rules, but cannot bypass successful-type suppression.
`RuleTable.build()` rejects state-changing cycles; same-state reactions are allowed.
| Situation | Rule |
| --- | --- |
| A message is admitted for an initialized trade. | Save it as pending work before its handler runs. Network receipt alone does not make it durable. |
| A message fails admission checks or is no longer relevant. | Reject or discard it without keeping it for later processing. |
| A relevant message arrives too early for its flow. | Keep it saved and reconsider it when processing advances. Other eligible inputs can still run. |
| A command cannot run in the current state. | Reject it without stashing it. A rejected command does not consume its event type. |
| Two messages of the same type are waiting. | Keep both, even if identical. Once one succeeds in a flow, discard the others for that flow. |
| A message type already succeeded in this flow. | Discard further inputs of that type, even with a different message ID or payload. |
| The first message is explicitly ignored. | Remove that message without consuming its type. A later valid message of that type can still succeed. |
| The same event type reaches another flow. | Check that flow's own history. Successful handling in one flow does not consume the type in another. |
| Work is still in the live submission queue. | It is memory-only until admitted and checkpointed. Queue entries and caller futures are not restored after a crash. |
| External non-command input arrives while a transition is unsaved. | Validate and route against the last saved snapshot, or the initial prepared snapshot before its first save. An unsaved history/state change must not permanently discard a new input. |
| A command waits behind accepted work. | This is queueing for its turn, not stashing until a business state changes. Once considered, unavailable commands are rejected. |
| One input addresses several flows. | Save remaining destinations after each step. Do not rerun a destination that already succeeded; another destination can remain deferred. |
| A transition raises internal events. | Route and save them with the transition. Ready raised work precedes older retained work and the next external submission; still-deferred work does not block ready work. |
| Reconsidering pending inputs. | Recheck after processor progress, including data/history changes without a named-state change. Stop when nothing progresses; removing an outgoing effect alone is not processor progress. |
| A handler fails before applying the input. | Do not record the original input type as successful. A successfully handled failure notification records its own type. |
| A command type already succeeded. | Treat its repetition as a no-op. A different, contradictory command is rejected by the current state/admission rules. |

The history key is **receiving flow + concrete input type**, not message ID or payload. This is a state-machine
policy for the current linear MuSig flows; a simple lane processor can support repeated input types. A second
mediation round would need both new transitions and an agreed duplicate scope.

Example: A arrives too early and is saved. B advances the flow and raises C. Save B, dispatch its outgoing work,
then process and save ready C before reconsidering A. If A still cannot run, leave it pending and accept later work.

## Checkpoints and processing order
| Situation | Rule |
| --- | --- |
| A checkpoint is written. | Save the trade, per-flow successful-type history, pending inputs, outgoing effects and timer deadlines together. |
| A transition produces outgoing work or internal events. | Save the new state and that work before dispatching the effects or handling the internal events. |
| An internal event causes another flow to fail. | Keep the producing transition. Each flow transition is saved separately; the receiving failure does not undo the earlier step. |
| Nothing in the checkpoint changed. | Skip the disk write. |
| Saving fails. | Pause further handler processing and keep the calculated result in memory. Retry saving without rerunning the handler or completed effects. |
| Another trade saves while this trade has a failed checkpoint. | Include only this trade's last successfully saved record. Publish a new record to the shared store only after its own checkpoint write succeeds. |
| Inputs arrive while saving or required setup is blocked. | Eligible messages can still be retained. Commands that cannot proceed fail without being retained for later execution. |
| A crash happens after backend work but before saving its result. | Recovery may repeat the backend call. Backend operations are assumed to be idempotent for now; that guarantee still needs verification. |
| Saving removal of a completed effect fails. | Pause. In the running lane, retry the save without repeating the effect. After a crash, the older checkpoint may replay it. |
| Writing a trade checkpoint. | Clone the shared store with the candidate record, write it, then publish it to the live shared store. Hold the persistence lock across those steps; another save cannot publish a failed candidate. |
| A save needs retry. | Retry the calculated checkpoint in the live lane. No independent background save retry exists; a later eligible input, scheduled input or startup may trigger recovery. |
| A failure is saved successfully. | Do not rerun the failed handler on pending-work retry or restart. Only saved outgoing work and remaining eligible reactions can continue. |

The normal sequence is:

```text
validate / route -> retain eligible message and save
-> evaluate one addressed step (may call the backend)
-> save state + history + pending inputs/effects/deadlines
-> process outgoing work; attempt best-effort logs
-> process ready internal work, with another save for each applied step
-> publish local state and complete this submission
```

A failed save or required effect pauses progression. A message handoff exception alone allows eligible processing
to continue under the rules below. Backend calls happen before their result's save, so save-before-dispatch is
not a claim that handlers are pure or that backend work and file persistence are atomic.

## Outgoing messages and logs
| Situation | Rule |
| --- | --- |
| Sending a saved message throws immediately. | Keep its original ID, payload and recipient for retry. Keep the committed state; other eligible inputs may still run. |
| Later outgoing messages are waiting behind a failed send. | Preserve their order across all recipients and flows in the trade. Do not let later sends overtake the failed one. |
| The send call returns without throwing. | Remove the saved send and checkpoint its removal. Do not wait for delivery, the returned network future or an ACK. |
| The returned network future reports failure. | Log it; do not put the message back in the lane. Further delivery follows the existing network behavior. |
| Pending outgoing work needs another attempt. | Retry through a later eligible submission or startup recovery. The service has no manual retry API, and the lane has no separate background resend timer. |
| A network ACK arrives. | Do not treat it as proof that the peer handled or persisted the message. |
| The lane removed a message, but network retry storage was not yet saved. | A crash can lose that message. This handoff gap is accepted for now; delivery is not guaranteed exactly once. |
| A best-effort log is produced. | Do not persist it for retry or replay. A log-writing failure does not pause processing. |
| A message handoff throws while other work is ready. | Attempt that failed message at most once per queued submission. Later message sends wait, but required timer/other non-message effects and eligible inputs can continue. |
| Buyer backend closure succeeds; final message handoff throws. | Keep local `TradeClosed` and the saved final message. The seller may remain waiting. The same send-failure rule applies in every execution state. |
| Trader sender/receiver ban causes a returned failed future. | Match V1: the adapter does not call the network. Because it returned rather than threw, the lane removes the saved message; lifting the ban does not replay it. |
| Peer is offline. | Existing direct/mailbox/network retry behavior applies, subject to its limits. The lane does not wait for the peer or implement a second delivery protocol. |
| Sending throws after network-side work already started. | Both layers may hold a retry copy. Lane retry preserves the original ID/payload/recipient; an exception does not prove the network accepted nothing. |
| An earlier effect or its removal checkpoint fails. | `processOutgoingWork` still attempts that transition’s best-effort logs in `finally`. Retrying saved effects does not rebuild those logs. |
| Local versus shared logs. | `Local` inserts into local chat using the supplied author; `Shared` uses existing chat delivery as the local user. Missing channels skip entries; posting/future failures do not fail the trade. |
| Automatic offer removal. | Maker account-payload handlers select `RemoveTakenOffer` when configured, after verification. The saved effect uses the existing offer service without rechecking the setting. Immediate and asynchronous failures are logged and the attempt is cleared; successful offer removal is not guaranteed. |

The network retry-store save can be delayed, skipped or fail after a normal handoff. Removing the lane's copy
therefore leaves an accepted crash window. Neither retries nor an ACK establish exactly-once peer processing.
The network layer is unchanged; [delivery follow-ups](todo.md#delivery-and-persistence) remain open.

## Required setup, timers and observation
| Situation | Rule |
| --- | --- |
| Required setup, timer operations or observation startup throw. | Pause further handler processing until retry succeeds. These infrastructure errors do not themselves fail the trade. |
| A timer is scheduled or restored. | Save its ID, event and absolute deadline. After restart, queue an overdue timer without starting a new waiting period. |
| A timer becomes due. | Replace its deadline with the pending input in one checkpoint before handling it. |
| A timer delivers a command with no destination. | Reject the command and save removal of its deadline. After that save succeeds, restart does not fire it again. |
| A timer is explicitly cancelled. | Save removal of its deadline and cancel the live timer. Ignore obsolete callbacks. |
| Deposit observation must resume after restart. | Recreate it from the saved flow state. Do not persist the live subscription. |
| A running observation reports a stream error. | Log it and continue eligible processing. Automatic reconnection remains deferred. |
| Required chat setup runs again after restart. | Repeat it safely before pending protocol inputs. Setup must be idempotent; its completion flag is not persisted. |
| Deadline is replaced or cancelled while a callback is queued. | Invalidate the old callback using scheduler identity/generation. A stale callback cannot apply the old timer. |
| A due-command removal checkpoint fails. | Retry that removal without rerouting the command in this live lane. An older on-disk deadline may fire again after a crash. |
| Observation-start call throws. | Remove its live registration, cancel the context and retain the saved effect. Pause further processing until required setup can succeed. |
| An observation stream invokes its error callback. | Remove the failed live registration and log; continue eligible processing. This also applies to a synchronous callback during a start call that returns normally. No automatic reconnection exists. |
| Restart needs a deposit subscription. | After setup, derive it from enabled saved flow state. Replace non-replayable observation requests with recovery effects, checkpoint them before dispatch, then process retained inputs. Live retries reuse this prepared work. |
| Development confirmation bypass. | Skip live deposit observation in development mode. `skipWaitForConfirmation` submits a development-only command using the same confirmation transition; production rejects it. |
| Chat setup sees an existing in-memory channel. | Still await its persistence before protocol processing. Channel setup is repeatable; trade and chat storage are not one atomic transaction. |

Timer deadlines are durable facts. Live subscriptions are not: recovery derives the need for observation from
saved state. A stream error does not recreate a completed observation effect; retrying other work will not
silently reconnect it. See [observation recovery](todo.md#execution-and-disputes).

## Failure scope and lifecycle
| Situation | Rule |
| --- | --- |
| A handler fails and produces a valid failure state. | Save the failure and its outputs. Do not automatically rerun the failed handler. |
| A trade is archived. | Stop business processing while retaining its record and saved outgoing messages for retry. |
| Input validation or command admission rejects. | `INPUT_REJECTED`: no handler, domain mutation, outputs or retained command. Explicit handler `ignoreInput()` instead consumes the input without changing successful-type history. |
| A wrapped backend/signing operation fails. | `OPERATION_FAILED`: persist terminal failure of the affected flow. Classify at the operation boundary, not merely from the exception’s Java class. |
| Other verification or handler processing fails. | `PROTOCOL_FAILED`: same terminal flow behavior. Ordinary verification exceptions are not automatically ignored. |
| Mediation case fails. | Stop the case and both decision children; preserve their recorded data/states. Execution and the trade parent remain unchanged. |
| One decision flow fails. | Stop only that child. The case, sibling decision and execution retain their own processing eligibility. |
| Execution fails locally or receives a valid peer error report. | Record `Failed` or `FailedAtPeer`, save `ExecutionFailedEvent`, then let the parent enter `Failed` in a separate step. Do not echo a peer error report; late reports cannot replace terminal execution. |
| Parent is `Failed`. | Stop all children, while allowing the parent’s archive transition. Failure propagation is a MuSig protocol rule, not generic lane policy. |
| Execution closes successfully. | Leave the parent `Active` until archiving. Local settlement commands cannot reopen it; otherwise eligible incoming mediation updates can still be recorded. |
| An active trade is archived. | Require normal closure, custom-payout closure or seller forced closure. A failed parent can archive regardless of child position. Preserve child data and pending outgoing messages. |
| An input targets a stopped flow or descendant. | Discard messages and reject commands before handlers. Derive stopped status from saved hierarchy/state, including after restart. |
| Processor cannot produce a valid failure snapshot. | Stop the lane and fail its submission exceptionally; do not invent a domain failure state. Restoration can use the last saved record. |
| Failure reporting itself fails or does not produce a failed flow. | Do not recurse through error handling. Propagate the original exception, attach reporting errors, and stop the lane. |

## Results and publication

`StepResult` describes one addressed processor step and its output. `ProcessingResult` summarizes the submission
and its ready reactions. `SubmissionResult` adds whether coordination completed. These transient results are not
persisted or updated when later work retries.

| Result or situation | Meaning |
| --- | --- |
| `ProcessingResult.Applied(snapshot)` | At least one step applied; no processing work from this submission remains pending. It does not itself promise a successful save. |
| `Deferred(snapshot)` | Some addressed work remains pending; other steps may already have applied. |
| `Ignored(snapshot)` | No destination applied or retained this input. |
| `Rejected(cause)` | No step applied and no work from this submission is retained. |
| `Failed(snapshot, cause)` | A step failed, or rejection prevented completing a partially applied/retained submission. The snapshot determines the affected flows. |
| `SubmissionResult.Completed(result)` | Required coordination finished for this attempt. The inner result can still be deferred, rejected or failed; completion does not confirm delivery. |
| `Pending(result, phase, cause)` | Coordination remains in `PERSISTENCE`, `SETUP` or `OUTGOING_WORK`. Retry the retained work, not an already-applied command. |
| `NotProcessed(cause)` | Earlier coordination prevented admitting this command. It was neither run nor retained. |
| Lane is closed or processor throws unexpectedly. | Complete the future exceptionally. |
| Service maps the result. | Public commands/notifications return completion-only futures; take-offer returns the ID. Pending/not-processed/failed/rejected outcomes expose their causes through failure, even if earlier steps applied. |
| Older retained input fails while a new input arrives. | Keep independent results. Publish/log the older flow failure; do not automatically assign it to the new submission. |
| Initial admission succeeds. | Publish the initial read model before its first save/setup; no model is invented for rejected admission. Later publications occur before submission completion. |
| Save or setup remains unsuccessful. | Retain independent `persistenceError` and `preparationError` until the corresponding operation succeeds. An unrelated input does not clear them. |
| Outgoing work remains pending. | Report through the operation future/logs, not `preparationError`; a dedicated outgoing-work read-model field is still deferred. |
| Snapshot observer throws. | Log it; do not fail processing. Observers must not wait for work on the same lane. |
| Situation | Rule |
| --- | --- |
| The read model shows a new state. | It shows local state, not proof of a successful save. Check persistence errors separately; rebuild the read model on restart. |
| A command future fails after its transition applied. | Do not assume the transition was undone. Retry pending coordination instead of repeating an already-applied command. |

Example: a payment command applies and is saved, but its send call throws. The API future fails, while the
read model already shows the payment state and the message remains saved. The failure does not mean payment
initiation should be submitted again.

## Startup and shutdown
| Situation | Rule |
| --- | --- |
| V2 is explicitly selected by application wiring. | Supply its own backend client and the application type directly. V2 setup initializes that client before the trade service and closes it only after the trade service drains. Main's V1 lifecycle remains unchanged; application wiring must select one implementation. |
| The application restarts. | Reconstruct all saved lanes, then queue their recovery before releasing incoming submissions. Routing need not wait for every recovery to finish. |
| Saved data cannot be read or restored safely. | Fail initialization instead of treating the store as empty and overwriting it. |
| The service shuts down. | Reject new submissions and drain work already accepted by lanes, including initialization. It may still create and save a trade and send its first protocol message. Preserve saved pending work and deadlines, cancel live timers and fail inputs still waiting for initialization. |
| Shutdown finishes while a save remains unsuccessful. | Only the last successful checkpoint is durable. Shutdown does not add a final-save retry. |
| Restoring a batch. | Validate every snapshot and addressed pending item before registering the batch. Unknown flow/history/state/payload data fails restoration; merely being too early for a state does not. |
| Recovery has been queued for all restored lanes. | Release startup inputs. Each lane queues them behind its own recovery; other trades need not wait for a slow or blocked owner. |
| Restoring ongoing work. | Finish setup, prepare/checkpoint recovery effects, restore absolute deadlines, dispatch pending work, then handle eligible retained inputs. Do not rerun completed handlers. |
| One recovery saves a domain failure. | Other trades can resume. A safely persisted failure is a valid recovery result; outstanding coordination or invalid restoration remains an error. |
| Reattaching the network listener. | Replay the network’s available processed-message cache. Overlap with live delivery is possible; per-flow history and rejected-ID memory handle their respective duplicates. |
| Shutdown begins before a buffered startup submission enters a lane. | Reject it. Already accepted lane work drains; pre-initialization waiters fail and live timers stop. Futures are not restored. |
| Attempting to reuse the current V2 service/router after shutdown. | They are single-use; the service returns its cached shutdown result. Settings-toggle support needs fresh activation resources and protection from old callbacks before V1 replacement. |
| Shutdown cannot confirm cleanup. | Keep the V2 client open if trade cleanup failed, report failure, and reject reuse of this V2 setup. Recovery policy and application-wide activation coordination are deferred. |

The current worker pool also has a separate overload defect: a rejected worker-start request closes that lane
permanently in its router. A bounded worker queue does not bound message queues. See [risks](risks.md); no
replacement pool or capacity policy has been implemented.

## Mediation and settlement

| Situation | Rule |
| --- | --- |
| No assigned mediator. | Do not create the mediation branch. Local mediation commands require an assigned mediator. |
| Local request. | Case must be `Init`. Takers first become eligible at `DepositTxSignedAndPublished`; makers at `AccountPayloadReceived`. Deposit confirmation is not required. |
| Later request/rejection positions. | Allow role-applicable `DepositTxSignedAndPublished`, `AccountPayloadReceived`, `DepositTxConfirmed`, `PaymentInitiated`, `PaymentInitiatedMessageReceived`, `PaymentReceiptConfirmed`. Exclude earlier setup, `CustomPayoutSigned` and terminal execution. |
| Mediator opens the case. | `Init` or `Requested` can enter `Open`. Incoming notifications do not use local-command execution eligibility. An early closing notification waits for opening. |
| Mediator closes the case. | Validate the signed result and enter `Closed`. This enables the decision children; it does not close execution. |
| Assigned mediator sends an invalid closing result. | Missing/invalid signature, wrong contract or invalid payout fails mediation and stops its children. A later corrected message cannot revive that failed flow. Arbitration is not implemented. |
| Result is valid `NO_PAYOUT`. | Keep the closed case; local accept/reject is unavailable and peer decisions are explicitly ignored without consuming their types. |
| Local rejection. | Require a closed case, signed payout proposal, own decision `Init` and an eligible execution position. Record terminal `Rejected` and notify the peer; normal settlement may continue. |
| Local acceptance. | Require a closed case, signed payout proposal, own decision `Init`, peer decision neither `Rejected` nor `Failed`, and execution at `DepositTxConfirmed`, `PaymentInitiated` or `PaymentInitiatedMessageReceived`. Save acceptance and the raised signing event before signing. |
| Normal settlement commitment. | Seller receipt confirmation / buyer payment-received processing prevents later custom signing. After custom signing, normal closure is unavailable. Rejection has the separate, broader eligibility above. |
| Peer acceptance and rejection compete. | First successfully handled decision wins, in saved arrival order. Both decision graphs are terminal; ignored mismatches do not establish a decision. |
| Wrong proposal hash on a peer decision. | Ignore without consuming its type, matching V1. A later valid message can still succeed. Other verification exceptions follow failure handling. |
| Our payout ID exists and the peer sends a different one. | Peer handler ignores before recording acceptance. Own signing state is preserved; a later matching peer message is eligible. |
| Peer accepts before we sign. | Save peer acceptance and its raised event with the PSBT. Execution defers that event until signing is ready; do not copy a shared mutable model or add a recording-only transition. |
| Peer accepted first, but our later transaction ID differs. | Keep both decisions accepted, execution at `CustomPayoutSigned` and the peer event saved. Do not finalize, replace the first payout or auto-recover; this is an interim unresolved mismatch. |
| Our decision rejects or fails before signing. | Mediation can record peer acceptance, but execution discards its now-useless raised follow-up. At `CustomPayoutSigned`, inconsistent agreement data instead reaches finalization verification. |
| Both accepted and payout IDs match. | Finalize directly from `CustomPayoutSigned` using the saved peer event. Verify the signed payout proposal and both decisions before `customCloseTrade`. |
| Finalization succeeds. | Save `CustomPayoutClosedTrade`, peer PSBT and backend response, then remove the pending input. |
| Signing/finalization fails. | Fail execution and then the parent; earlier accepted decisions remain. Terminal finalization failure discards the peer input without adding its PSBT to execution. No automatic retry of that failed operation. |

The acceptance and signing steps are intentionally separate saves. If acceptance's checkpoint fails, signing has
not started. If signing succeeds but its save fails, retry that save in memory. A crash can still repeat backend
work. A failed mediation child after signing can leave settlement blocked; this is open work, not implicit
permission for a failed flow to resume.

### Mediation chat and payment details

| Situation | Rule |
| --- | --- |
| Local request or mediator opening. | Save `ActivateMediationChat` before the message effects. Missing channel/activation failure retains the effect and blocks required work. Closing the case leaves mediator chat routing active, matching V1. Chat activation persistence is asynchronous and not atomic with the trade store. |
| Case opens directly from `Init`. | Send saved `MuSigDisputeCaseDataMessage` with local contract hash and pruned existing history to the assigned mediator. Opening from `Requested` does not resend this report. Retry uses the saved history snapshot. |
| Opening notice. | Emit the V1 local notice attributed to the mediator, with requester/counterparty wording. It is best effort under the logging rules, not durable pending work. |
| Request announcement. | Build the request from existing history, save and activate chat, hand off the request, then post the shared announcement. Unlike V1, the announcement is absent from the request’s attached history. |
| Announcement reaches the mediator first. | Existing chat code buffers it in memory until valid case/channel creation, then imports request history with existing duplicate handling. This ordering support is not a durability guarantee. |
| Local acceptance/rejection log. | Emit rejection at the decision step; emit acceptance after successful execution signing. Use the channel’s local username; absent channel skips the entry. |
| Mediator asks for payment details. | Validate trade ID and assigned mediator. Stash in case `Init`; answer in `Requested`, `Open` or `Closed` without changing state. Read local payload from trade data and peer payload through execution’s view, ordered as taker/maker. |
| Peer account payload is unavailable. | Ignore the request without consuming its type or keeping it for automatic replay. A later request can succeed. |
| Payment response is produced. | Save response, receiver and successful input type before sending. Answer once per mediation flow, including across restart; later requests do not create a fresh response. |
| Parent/case stops. | Failed/historical parent or failed mediation does not answer new requests. Successful execution closure alone leaves the active parent eligible. |

Sources: [mediation case](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/mediation/MuSigMediationProtocol.java),
[own decision](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/mediation/MuSigMyMediationDecisionProtocol.java),
[peer decision](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/mediation/MuSigPeerMediationDecisionProtocol.java),
[finalization](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/CustomPayoutFinalizationHandler.java) and [chat adapter](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigTradeChatService.java).
Timeout/force-closure behavior and pinned backend limitations live in [closure comparison](closure-comparison.md).

## Serialization

| Item | Persisted representation and restoration |
| --- | --- |
| Flow snapshots | Concrete Java state/data classes have matching protobuf messages; composite snapshots store children beside data. States use their matching `oneof`. Missing/unknown required states fail restore. |
| Generic `Event` | Requires `Proto`; `common.Event` wraps the concrete protobuf in `Any`. Local events use `TradeEvent.muSigTradeEvent`, matching their Java hierarchy and `Event` suffix. |
| P2P messages | Shared `TradeMessage` / `MuSigTradeMessage` implement the common input contract; network formats stay unchanged and V1 has no V2 event dependency. Mediator messages adapt to distinct opened/closed/payment-details local events. |
| `Delivery` | Save stable destination flow name and event. Unknown flow names fail restore; pending order and payloads must survive conversion. |
| Processing history | Per-flow sets of stable `MuSigV2EventType` identities. Unknown flows/types fail restore. Ignored inputs are not recorded. |
| `ScheduledEvent` | Save timer ID, absolute deadline and event. Scheduling is handled by the lane; live futures are not serialized. |
| Effects | Require `PersistableProto`; common effect wrappers include typed message/receiver payloads, scheduled events and cancellation. No MuSig-specific generic work wrappers are needed. |
| Resolver ownership | `StateMachineResolver` uses the existing `ProtoResolverMap`. Application `ResolverConfig` registers local inputs/effects and delegates P2P decoding through existing network resolution. `MuSigV2TradeRecord` composes conversions, not its own payload registry. |
| Startup ordering | Configure resolvers before loading persistence. Isolated trade tests use `MuSigPersistenceTestSupport`; local events/effects are not network whitelist entries. Wrong-category or malformed payloads fail restoration. |
| Command classification | Comes from the Java `Command` marker after restoration; conversion rejects a conflicting pending-work flag. No separate command behavior flag is added to event protobufs. |
| Schema compatibility | V2 is undeployed. Old prototype records without the current required snapshot/work fields are rejected, not silently migrated or treated as an empty store. |

Implementation entry points: [trade record](../../../trade/src/main/java/bisq/trade/mu_sig_v2/MuSigV2TradeRecord.java),
[generic processor contract](../../../common/src/main/java/bisq/common/processing/Processor.java),
[submission result](../../../common/src/main/java/bisq/common/processing/SubmissionResult.java) and
[processing lane](../../../common/src/main/java/bisq/common/processing/ProcessingLane.java).
