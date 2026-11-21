# MuSig V2 open work

Open decisions and follow-up work, grouped by topic. The first entry is the most important one. No entry
authorizes a behavior change which is not agreed. Start with the [architecture overview](architecture-overview.md).

## Trade protocol

- **A failed trade has no way out after the deposit.** Once the deposit transaction is published, the Bitcoin of
  both traders is locked. A failure after that stops every step of the trade for good, including the seller's
  close after 24 hours and mediation. One failed backend call, or one error report from the peer, is enough. V1
  ends in the same way. Three steps would resolve it: (1) after the deposit, the peer's error report no longer
  ends the trade; (2) a failed step of the own node no longer ends everything: a failed backend call is tried
  again, and mediation stays possible; (3) a way out without the peer, through a mediator or an arbitrator.
  "After the deposit" starts for the taker when it has published the deposit transaction, and for the maker when
  it has sent its signatures for it. Nothing is decided.
- **Implement buyer recovery.** The buyer's 24-hour timeout only logs and stays in `PaymentInitiated`. A forced
  close needs the seller's signed swap transaction, and the backend has no way yet to observe it. Also decide what
  happens with trades whose timeout was already consumed. Cover late messages, timer races and a restart.
- **Define seller recovery after custom signing.** The backend rejects the seller's forced close from that state.
  Agree a mechanism, its trigger and its timing first. Cover late messages, timer races and a restart.
- **Arbitration after a failed mediation.** Define how a trader starts arbitration after an invalid closing result
  of the mediator failed the case, and which evidence the node keeps. The lane ignores messages for a stopped
  flow, which relies on a failure being final (`MuSigFlowStopsAreFinalTest`).
- **An invalid closing result of the mediator ends the case.** A closing message with a wrong signature, contract
  or amounts fails the mediation flow for good, and both decisions with it. Only the trade's mediator can send
  it. V1 ignores such a message, and a later valid one still closes the case. Decide which behavior V2 keeps.
- **Mediation or decision failure after custom signing.** A failed case or decision flow can stop the path which
  delivers the peer's payout data to execution. Decide whether the parent fails as well, or whether execution gets
  its own settlement input. A failed flow must not resume.
- **Make payout mismatches explicit.** A peer decision can be accepted before the node knows whether the payout
  IDs match. Decide how a blocked finalization is shown, what the node keeps and what recovery means. A final
  finalization failure discards the peer's PSBT today.
- **Recover a failed deposit observation.** A failed observation stream is only logged, so a confirmation can be
  missed until a restart. Decide trigger, backoff and reporting first. Avoid duplicate subscriptions.
- **Keep the messages which arrive during a trade restriction.** The router refuses them, as V1 does, and the
  peer does not send them again. So the trade can still wait for such a message after the restriction. This
  also holds for a saved outgoing message which a lane sends during a restriction: a restricted receiver
  refuses it. The lane can now pause a kept input, so it could keep these messages and handle them afterwards.
  This is a security decision; see "Open trades across versions" in the
  [trade restrictions](../../specifications/trade/trade-restrictions.md).
- **Fresh payment-details response.** Decide whether a mediator needs a second response after the first one
  succeeded. Establish a use case first.

## Backend

- **Verify backend idempotence after a crash.** A restart can repeat a backend call which succeeded before the
  save. Verify for setup, signing, publication and closure that a repeated call is safe. Tests with a mocked
  backend cannot show this.
- **Handle temporary backend errors.** Any backend error fails the flow for good and fails the trade. The backend
  does not tell a permanent error from a temporary one, and it has no safe repeat: after a timeout it may already
  have done the work. A safe first step: pause and retry when the backend is unreachable before a handler's first
  call. Full retries need the backend side.
- **Check a paused forced close against the backend.** During a trade restriction the seller's forced close
  waits and runs when the restriction ends. Verify with the backend side that no time limit of the MuSig
  protocol can pass meanwhile to the seller's cost.
- **Limit the duration of backend calls.** The blocking gRPC stub has no deadline. A backend which never answers
  blocks a worker, and five such calls stop all trades of the node. Agree time limits per operation first; today a
  timeout would fail the trade.

## Memory, saving and delivery

- **Bound retained work.** Done: messages for unknown trade IDs wait in one list of at most 100. Still without a
  limit: lanes created by take offer requests whose initialization has not run, each lane's input queue, and
  copies kept inside a trade. Continued traffic can exhaust memory. One shared limit on unprocessed incoming work
  could cover the first two; it must never limit local commands, timers, retries or recovery.
- **Measure the cost of a save.** Each save clones and rewrites the whole trade store and holds the persistence
  lock. Measure with realistic numbers of trades before optimizing.
- **Consider a backoff for automatic retries.** The lane retries a failed save, setup, effect or send every second
  while the failure lasts. Open: growing delays instead of the fixed second.
- **Decide whether local reactions should precede outgoing delivery.** Today the lane saves one transition,
  dispatches its effects and then processes the raised events. Keep this until decided.
- **Verify the live sending.** Test immediate exceptions, failed futures, offline peers and the mailbox path with a
  real network. The pair tests use a mocked network.
- **Close the network handoff gap if required.** After a send returned, the lane removes the message before the
  network has saved its own retry record. A crash in between can lose the message. Accepted for now.
- **Systematic restart coverage.** Before V2 replaces V1, restore each role and each mediation order after every
  saved step, and check state, history, pending work and deadlines.

## Lifecycle, chat and API

- **The chat channel of a trade is not compared with the trade.** A trade finds its chat channel by the trade ID
  only. On a node which is also a mediator or an arbitrator, a request can create a channel for any trade ID. Done:
  the maker does not start a new trade if such a channel exists. Open: a channel which appears between this check
  and the chat setup is taken as it is. To close it, the chat service must create the channel and fail if one
  exists, or the trade must compare the channel with its own data, as Bisq Easy does.
- **Integrate V2 with the application and the UI.** Support the settings toggle between V1 and V2, finish the
  routing of arbitration, and wire the read model into the UI. `MuSigTradeInitializer` still reaches the selection
  of mediator and arbitrator through the V1 trade service; this waits until V1 code may change or is removed.
- **Save the ID of the deposit transaction.** The trade details of the desktop show this ID and open it in a block
  explorer. V1 shows the placeholder "TODO depositTxId", and V2 keeps no ID. The confirmation status of the backend
  has a field with the transaction, so the deposit confirmation step can derive the ID and save it, and the read
  model can show it. Check the derived ID against real backend data, because the tests mock the backend.
- **Review the results of service operations.** A future fails both for a rejected command and for an applied one
  with pending work. Decide whether callers need to tell these apart. One option, not agreed: map every outcome to
  a `MuSigProtocolException` with its own category; this conflicts with keeping the original exception types. Also
  decide whether `SubmissionResult.Pending` needs its phase and its partial result; `MuSigTradeService` reads only
  the cause.
- **Define recovery after a failed shutdown.** V2 is single-use and cannot repeat a failed shutdown. This matters
  for the settings toggle.
- **Discuss the closing of the chat channel.** Decide what closes the chat: the end of the trade, archiving or the
  user. Archiving does not close it today.
- **Delete or redact historical trades.** An archived trade stays in the store for good, with the payment account
  data of both traders. Every save writes all trades again, and every start restores a lane for each. Decide
  whether the user can delete a trade from the history, or whether old payment data is removed after some days,
  as Bisq Easy does. A deleted trade needs a saved list of deleted IDs, because a repeated take offer request
  can create it again.

## Generic layer

- **Move the mediator's flows to the state machine.** Planned after the trader protocol. Decide then whether the
  generic classes keep the event family type parameter `E`; MuSig only uses `Event` for it.
- **One abort path for unexpected exceptions in the lane.** Today such an exception closes the lane only where
  `callProcessor` wraps the call. One path would be simpler, but a bug which fails one submission today would then
  close the lane until a restart. Revisit if such a bug appears.
- **The deposit observation is declared three times.** Two handlers return the effect
  `ObserveDepositTxConfirmation`, `MuSigExecutionProtocol.recoveryEffects` repeats the rule for a restart, and
  this effect is the only one which overrides `replayOnRestart`. One rule "while the trade is in
  `AccountPayloadReceived`, observe the deposit" could replace all three, as the lane follows the saved
  deadlines for timers. Parked: such a rule would be a new concept in the lane.

## Branch preparation

- **Plan commit and PR boundaries.** Separate the generic processing and state machine code from the MuSig V2
  code. Give the changes of shared code their own commits: the stricter checks of dispute requests in the
  `support` module, the opened and closed messages of the mediator and the arbitrator, the four additions which
  the [decisions](decisions.md) name, and the two tests added to the `persistence` module.
