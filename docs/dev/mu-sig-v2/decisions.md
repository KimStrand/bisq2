# MuSig V2 decisions

Why the design is as it is, and where V2 differs from V1 on purpose. Start with the
[architecture overview](architecture-overview.md). The
[input specification](../../specifications/trade/mu-sig-v2/input-processing.md) defines the required behavior.
The [todo list](todo.md) holds the open work, including the limits which are accepted for now.

## Design decisions

- **V1 code stays unchanged.** V2 calls V1 classes but does not change what V1 does, except for the next two
  entries. Four additions exist which no V1 code calls: `MuSigContractVerifier.verifyPeerAccountPayload`,
  `NonceSharesRequestUtil.createRequest`, the interface `Event` on `TradeMessage` and on the three messages of
  the mediator (so that these messages can be inputs of the V2 rule tables), and
  `MuSigOpenTradeChannelService.addLocalTradeLogMessage` in the chat module. V2 gets its own backend client and
  manages its start and shutdown; the shared `ServiceProvider` and the V1 lifecycle stay as they are.
- **The mediator and the arbitrator check a dispute request more strictly.** This also affects V1 trades. Both
  compare the parties of a request with the contract by key, not by the whole network ID. Both ignore a request
  in which requester and peer are one profile, or in which a trader has the key of the mediator or arbitrator.
  Rule 3 of the [dispute agents of MuSig trades](../../specifications/support/mu-sig-dispute-agents.md) gives
  the reasons.
- **One message for an opened case and one for a closed case.** The mediator and the arbitrator sent one
  message with a case state and an optional result. Each now sends an opened message and a closed message,
  and a closed message always carries the signed result. V1 uses the same messages, so this also affects V1
  trades. MuSig runs only in developer mode, so the old message type has no compatibility handling.
- **A serial lane and a synchronous processor.** The lane owns order, saving and delivery. The protocol
  definitions own the business transitions. The generic processor also supports simple entities without a state
  machine.
- **Backend calls run synchronously inside the handlers.** This avoids extra asynchronous business states. The
  cost: a call occupies a shared worker, and a crash between the call and the save can repeat the call. That a
  repeated call is safe is assumed, not verified.
- **Messages for unknown trades wait in the router.** One list of at most 100 messages for all trades, oldest
  dropped, as in V1. Equal messages stay separate entries; V1 ignores an equal waiting message. Only an
  initializer or a restored trade creates a lane. A flood can push out a real early message, as in V1.
- **The memory of rejected IDs is limited to 1,000.** The registry forgets the oldest ID. A restart forgets every
  ID anyway, so a forgotten ID adds no new kind of risk: a copy of its request runs admission again. A freshness
  rule for take offer requests was not chosen, because it could reject honest requests which arrive late through
  the mailbox.
- **Lane work runs on a fixed pool of five workers.** The waiting queue of the pool has no limit, so a temporary
  overload delays lane work and does not close lanes. Only a stopped executor rejects work. Under lasting
  overload, lane work waits without a time limit; this cost is accepted.
- **State and collected data are separate.** A typed state expresses the position in the protocol. The execution
  data keeps the signatures and transactions across the steps, so that no state record has to pass them on.
  Optional data still needs a check at runtime.
- **Every flow has its own state and data.** A check of one flow reads the other flows through read-only views. A
  flow changes another flow only through a raised event.
- **Typed transition rules, with admission at runtime.** The compiler checks that source state, event, handler
  and target state fit together. It does not check business correctness or whether optional data is present.
- **The trade lifecycle is a parent flow.** Closing the execution and archiving the trade are separate steps. How
  a failure spreads is a rule which the parent declares.
- **Each complete input attempt is saved before its reactions.** A restart resumes a saved step without repeating
  it, and a later failure does not undo it. The cost: an input and all its reactions are not saved as one unit.
- **Commands and messages have separate step orders.** The network does not send a message again, so the lane
  checks a message against the last saved state and saves it on arrival. A command comes from the local user: the
  lane first finishes earlier work, then checks the command against the current state and keeps it in memory
  only. If the earlier work cannot finish, the lane does not attempt the command (`NotProcessed`).
- **A message which can no longer be handled is not saved.** That is the case when no flow is interested in the
  message, or when its flow has already handled its type, has stopped, or can no longer reach a state which
  handles it. None of these can change back. `MuSigFlowStopsAreFinalTest` guards that a stopped flow stays
  stopped. The discard rules of the flows and a handler's own ignore are not part of this check, because they
  need not be final.
- **Outgoing work is saved with the transition.** A retry delivers the original message without repeating the
  signing. A failed send does not fail the trade.
- **A timer is not an effect.** It is an input for later, as a raised event is an input for now. A handler
  returns timer changes in their own list, next to its effects and its raised events. The lane saves a timer only
  as its deadline, and its timers follow the saved deadlines, in the running process and after a restart. A second
  saved entry as pending work repeated the deadline and cost one more write for each timer start and cancel.
- **A trade restriction pauses the inputs which a lane keeps.** The router refuses an input which arrives during a
  halt of trading or below the minimum version. But the lane saves an input before it handles it, and it saves
  each reaction to an input as an input of its own. So a saved message, the taker's first step, a due close
  timer, or the signing or finalization of a mediated payout can become ready during a restriction. The lane
  asks the input policy before it starts a handler and pauses such an input; it stays saved. The service
  observes the alerts and lets the lanes continue. A command of the user is never paused, because the lane does
  not keep a command.
- **Each flow handles an input type once.** This matches the linear protocol of V1. An ignored input does not use
  up its type. An operation which may repeat would need a new decision.
- **The read model is mutable and separate from the saved data.** It uses the existing observables. Each trade
  keeps the same read model object, which UI bindings need. The startup rebuilds it.
- **`InitializeTrade` carries the resolved terms of the take offer.** The application resolves the participants,
  the mediator and the arbitrator, and passes on the market price of the review step. The lane checks the trade
  restrictions again, but it does not select other terms.
- **Optional work is best effort.** A failed log, contact entry, offer removal or activation of the
  mediation chat does not stop the trade. The chat activation runs before the messages and logs of its step,
  because the mediation mode of the chat decides whether chat lines also go to the mediator. Required setup, the
  timers and the start of the observation keep their stricter rules. A log line is not saved: the lane writes it
  once, from memory, after the save of its step. So a line is lost if the node stops between that save and the
  write, as in V1. A saved line would cost one more save for each line and could appear twice after a crash.
- **Checks and handlers get separate contexts.** A check gets `MuSigReadOnlyContext` with read-only views of the
  flows, so it cannot call a service or the backend. A handler gets `MuSigProtocolContext`. All flows of a
  processor share both contexts, because no flow needs its own.
- **Commands keep their V1 names.** A user action keeps the V1 name which ends in `Event`, for example
  `PaymentInitiatedEvent`. The marker interface `Command` shows that an input is a command. A rename would differ
  from V1 and change saved type names.
- **`MuSigProtocol` is the base class of all protocols.** As in V1, it holds `VERSION`. It also holds the list of
  flows and names the event family and both contexts once. Every protocol is named after its flow, so
  `MuSigTradeProtocol` defines the TRADE flow.
- **The setup handlers keep the V1 class layout.** The setup messages B, C and D keep one base class and one
  subclass per role, so that V2 can be compared with V1 class by class. The compiler cannot tell the buyer class
  from the seller class; the pair tests (`MuSig*PairTest`) fail if a role protocol uses the wrong one.
- **The execution flow says whether it can sign the custom payout.** The check of the local trader's acceptance
  asks the execution flow (`canSignCustomPayout()`), and the role protocol answers from its rule table. So the
  states in which a trader can accept exist once, as in V1.
- **Saved data uses the existing protobuf patterns.** `Proto` and `PersistableProto`, with explicit restoration
  and resolver registration. V2 adds no codec of its own.

## Deliberate V1 differences

| Area | V2 choice |
| --- | --- |
| Immediate send exceptions | Preserve the saved transition and pending message in all states. V1 can fail the trade after the backend operation succeeds but the send call throws. |
| Duplicate scope | V2 filters every successfully handled type per flow, including same-state reactions. V1 excludes `.stay()` transitions from its processed history and also relies on handler-specific duplicate checks. |
| Caller reporting | Futures can report rejection or unfinished coordination separately from observable trade status. V1 primarily exposes protocol/model state. |
| Recovery | Restore all owners before queueing recovery; preserve per-trade ordering and isolate safely saved domain failures. |
| Mediation request | Local requests require the deposit publication/receipt milestone. Incoming mediator notifications have separate eligibility. The request takes both user profiles from the trade chat, as Bisq Easy does. V1 reads them from the profile service, where the profile of a peer who stays offline expires after 15 days. |
| Mediation decisions | Separate terminal decision flows. The first successfully handled peer decision wins in arrival order; V1's buffered settlement handling replays rejections before acceptances. |
| Custom finalization | One execution transition saves the peer PSBT on success. Unlike V1, a terminal finalization failure does not preserve that PSBT in execution data. |
| Payment-details requests | One successful response per mediation flow, including after restart. V1 has no equivalent persisted once-per-type history for this request. |
| Trade restrictions | A due close timer waits during a restriction and runs when it ends; V1 refuses it and does not run it later. Paused inputs continue as soon as the alerts change; V1 continues at the next change of the trade or at the next start. The deposit confirmation is handled during a restriction, because its handler only records the fact; V1 refuses it. |
| Timers and force closure | Persist absolute deadlines. Buyer timeout is a placeholder reaction, not implemented force closure; no automatic mediated closure after 24 hours. |
| Completion date | The step which closes the trade saves the date in the execution data, and a closed state without the date cannot exist. A failed trade has no date. V1 sets the date in the desktop app, when the user first opens the view of the closed trade. |
| Sender of the trade messages | Every message carries the node's network ID of the contract as sender. That is the network ID which the peer stored (rule 3 of [identity by key](../../specifications/user/identity-by-key.md)). V1 MuSig does this in some handlers. In the others it uses the network ID of the identity, whose addresses can be newer. |
| Chat channel of a new trade | The maker does not start a new trade if a chat channel with its trade ID exists already. V1 MuSig takes such a channel as the chat of the trade. A channel which appears between this check and the chat setup is still taken; see the [todo list](todo.md). |
| Chat channel of the maker | The setup of every trade creates its chat channel before the first handler runs, because the message and the log line of that handler need it. V1's maker creates the channel after the first handler has verified the take offer request. A failed first step leaves an empty channel. |
| Contact list entry | Both roles add the peer to the contact list when the first step of a new trade has succeeded. V1's maker does the same. V1's taker adds the peer when it creates the trade. |
| Closing a trade | Archiving keeps the trade as a historical record, with its data. V1 removes the trade from the store, and its code calls that temporary. |
