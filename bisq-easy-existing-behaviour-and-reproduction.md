# Bisq Easy existing behaviour and reproduction findings

Date: 2026-08-13

## Purpose

This document describes the existing message-delivery and Bisq Easy trade-state behaviour before discussing any fix.

It deliberately separates:

- what users and developers reported;
- what the released code demonstrably does;
- which failure modes we reproduced;
- which architectural risks are visible in the code but were not reproduced end to end; and
- what remains unknown about the production incident.

The purpose is not to assess PR #4885 or another proposed implementation. It is to establish a shared problem description first.

## Current result

The investigation now has one joined, deterministic baseline reproduction that closely matches the reported symptom and restart recovery:

- the same valid, signed take-offer request is decrypted and delivered once by each of two independent confidential-message services;
- their listener callbacks overlap and enter the real Bisq Easy trade service;
- two concurrent creations for the same trade ID can produce two different in-memory trade objects;
- the persisted set and UI keep one object, while the protocol map keeps the other;
- a user action is routed to the protocol object's state, so the UI object's state does not change;
- real disk persistence snapshots the unchanged UI object; and
- after disk restore and service initialization rebuild the protocol from that persisted object, retrying the same action advances the object observed by the UI.

This is a concrete, restart-recoverable split-model defect in the unchanged release code. It is a stronger candidate for the reported “button does not work until restart” behaviour than ordinary FSM event reordering.

It is not yet proven to be the exact cause of issue #1622. The joined test invokes two real confidential-message services directly, but it does not establish actual Tor/I2P connections, exercise mailbox storage or ACK handling, or use a trace from an affected production trade. Timing gates deliberately force the unsafe but production-reachable interleaving. A full two-peer transport reproduction or production evidence is still needed to connect this mechanism to the field report.

## Evidence labels

The following labels are used throughout the document:

| Label | Meaning |
| --- | --- |
| Reported | Stated in the issue or a comment, but not independently reproduced here. |
| Reproduced | Demonstrated deterministically against unchanged baseline code. |
| Code-confirmed | Directly established by reading the unchanged source path. |
| Inferred | A plausible consequence of reproduced or code-confirmed behaviour. |
| Unknown | The available evidence is insufficient to decide. |

## Baselines used

The primary baseline is the exact version named by the affected user:

- Bisq 2 desktop `v2.1.11`
- commit `3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295`

The same characterization suite was also run against the unchanged target branch used by the mobile work:

- `for-mobile-based-on-2.1.11`
- commit `10c136466ae8da3e7954661acea6e6d915ee5318`

No proposed fix was present in either test worktree.

## The production report

The source report is [bisq-mobile issue #1622](https://github.com/bisq-network/bisq-mobile/issues/1622).

### Reported facts

- The affected user was running Bisq 2 desktop version 2.1.11.
- The behaviour was intermittent and occurred across several real trades.
- Payments and trade chat continued to work.
- The normal progression controls, particularly “Mark Payment Sent” and “Payment Received”, sometimes did not work or did not advance the trade correctly.
- The traders completed or documented the affected trades through chat.
- The problem was gone by the following morning without a known configuration change.
- The original user could not say which clients or versions the counterparties used.
- A developer later reported separately that they reproduced a desktop problem and that restarting the relevant core process recovered it.

### Information missing from the report

The report does not provide:

- the affected trade IDs;
- timestamps or logs;
- the local and remote trade roles;
- the precise FSM state before and after the failure;
- the exact button action or inbound protocol message that failed;
- whether the sender received an ACK;
- whether the message used direct delivery or mailbox delivery;
- the order in which the relevant messages reached the core node; or
- deterministic reproduction steps for the developer's restart-recoverable case.

Therefore, the exact production incident has not been reproduced in this investigation.

The statement “the FSM issue was found” in the issue discussion is a diagnosis made by the contributor. The issue record itself does not contain enough observations to independently verify that diagnosis or identify one specific FSM failure mode.

## The three in-memory holding areas

Several different collections have been referred to as a queue or replay buffer. They must not be treated as the same mechanism.

| Holding area | Used when | Retry trigger | Persisted? |
| --- | --- | --- | --- |
| `ConfidentialMessageService.processedEnvelopePayloadMessages` | A confidential message was decrypted and accepted by the network service. It also filters duplicates and supplies startup replay. | A consumer explicitly reads it during its initialization. | No; it is cleared on network-service shutdown. |
| `BisqEasyTradeService.pendingMessages` | A Bisq Easy message arrives but no protocol exists for its trade ID. | A later message is successfully routed through an existing protocol. | No. |
| `FsmModel.eventQueue` | The protocol exists, but the current FSM state has no transition for that event class. | Every later successful FSM transition immediately retries the queued events. | No in v2.1.11. |

These collections address different ordering boundaries:

```text
encrypted network message
        |
        v
ConfidentialMessageService processed-message set
        |
        v
BisqEasyTradeService pendingMessages (only if no protocol exists)
        |
        v
protocol / FSM eventQueue (only if the protocol exists but the state is too early)
```

## Existing message-delivery path

The following is code-confirmed for v2.1.11.

1. The network service starts before the trade service in both desktop and API/trusted-node applications.
2. A confidential message is decrypted and validated by `ConfidentialMessageService`.
3. The decrypted payload is inserted into the in-memory processed-message set.
4. Listener callbacks are submitted asynchronously to the shared network notification executor.
5. The network processing method returns success without waiting for those listeners to finish.
6. For mailbox delivery, that success allows the receiver to remove the mailbox entry.
7. `MessageDeliveryStatusService` is one of the listeners. For an ACK-requesting message, it can send an ACK after validating the message's basic fields and receiver identity; it does not wait for the trade FSM to apply the message.
8. Once the sender receives the ACK, its resend service stops retrying and removes the stored resend entry.

Relevant unchanged sources:

- [application initialization order](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/apps/desktop/desktop-app/src/main/java/bisq/desktop_app/DesktopApplicationService.java#L273-L303)
- [confidential message processing and asynchronous listener notification](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/services/confidential/ConfidentialMessageService.java#L485-L523)
- [mailbox removal after network processing succeeds](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/services/confidential/ConfidentialMessageService.java#L136-L147)
- [ACK generation](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/services/confidential/ack/MessageDeliveryStatusService.java#L193-L211)
- [sender stops resending after receipt](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/services/confidential/resend/ResendMessageService.java#L223-L248)

The important boundary is:

```text
network accepted/decrypted != trade FSM applied
```

An ACK is evidence that the receiver's network layer accepted the message. It is not evidence that the trade protocol changed state.

## Existing FSM behaviour

For the concrete seller-as-maker sequence examined here:

- the seller has already sent the account data;
- the seller has not yet processed the buyer's BTC-address message; and
- the buyer's confirm-fiat-sent message is valid only after the BTC-address transition.

For clarity, this document calls the two inbound messages:

- `B`: `BisqEasyBtcAddressMessage`, the missing prerequisite;
- `C`: `BisqEasyConfirmFiatSentMessage`, the future event.

The relevant seller-as-maker transitions are defined in [BisqEasySellerAsMakerProtocol](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/java/bisq/trade/bisq_easy/protocol/BisqEasySellerAsMakerProtocol.java#L69-L101).

The released FSM behaves as follows:

1. If `C` arrives while the trade is still waiting for `B`, there is no matching transition, so `C` is added to `FsmModel.eventQueue`.
2. If `B` later arrives, it performs a successful transition.
3. Immediately after that transition, `Fsm.handle()` retries every queued event.
4. `C` now matches the new state, is applied, and is removed from the queue.

This retry is visible in [Fsm.handle](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/common/src/main/java/bisq/common/fsm/Fsm.java#L63-L116).

Therefore, ordinary runtime arrival order `C, B` does not by itself leave this trade stuck.

## Reproduction method

Four baseline characterization tests are stored at:

`review-artifacts/existing-behaviour/BisqEasyTradeExistingBehaviourTest.java`

SHA-256: `4c1c4bbcd0ffe2005b00eb5b08505030c51a313f77f49381eeffcbbbe8de468d`

`review-artifacts/existing-behaviour/BisqEasyDuplicateProtocolCreationEvidenceTest.java`

SHA-256: `61aa003d7c3631a68d76295ffbbc85960c1436bffa41806e5f8f94da334dbe52`

`review-artifacts/existing-behaviour/ConfidentialMessageCrossTransportDedupEvidenceTest.java`

SHA-256: `6ee247a36c72b0ec42eef5dcc2d73e7d5f2560d9619fc1a7f3cd9cd4674cb565`

`review-artifacts/existing-behaviour/BisqEasyCrossTransportLifecycleEvidenceTest.java`

SHA-256: `d42058c70b0017be523a86093e6c5826fe89d6a1336d0cacd1ee805f66c9ca47`

The first file uses the real Bisq Easy seller-as-maker protocol, real trade-service routing, real FSM transitions, and real trade protobuf round-trip. It also delivers 64 pairs of prerequisite/future messages concurrently through the real trade service. The service-provider dependencies are mocked, and the startup handoff test mocks the confidential-message boundary to force one precise interleaving.

The second file uses the unchanged trade service and protocol implementation. Timing gates around the existing public guard and persistence calls deterministically force the two unsafe creation calls to interleave. Reflection is used only to invoke the private factory and to place the two models in the same pre-action state. The user action, FSM transition, store serialization, service initialization after restart, and retried action all use the real production paths.

The third file creates two real confidential-message service instances, gives both the same receiver key, and passes the same encrypted payload to them. It also repeats the payload within the first service. The test proves that the repeat inside one service is suppressed, while the second service delivers the payload again and the two listener callbacks overlap.

The fourth file joins the network and trade-service behaviours. It encrypts and signs a real `BisqEasyTakeOfferRequest`, decrypts it through two independent real confidential-message services, and connects both listener callbacks directly to the real trade service. Timing gates force both callbacks through the existing non-atomic creation guards. Request validation, protocol handling, the later user action, FSM transitions, disk persistence, persisted-store reading, service initialization, and the retried action use production implementations. Reflection is used only to place the two split models at the later pre-action state so the test can isolate the user-visible consequence.

These are integration/characterization tests, not full two-peer Tor/I2P/mailbox/ACK end-to-end tests.

The files were copied unchanged into clean detached worktrees and run with:

```text
./gradlew :trade:test \
  --tests bisq.trade.bisq_easy.BisqEasyTradeExistingBehaviourTest \
  --tests bisq.trade.bisq_easy.BisqEasyDuplicateProtocolCreationEvidenceTest \
  --tests bisq.trade.bisq_easy.BisqEasyCrossTransportLifecycleEvidenceTest

./gradlew :network:network:test \
  --tests bisq.network.p2p.services.confidential.ConfidentialMessageCrossTransportDedupEvidenceTest
```

Results:

| Baseline | Ordering/lifecycle suite | Split-model reproduction | Cross-service duplicate delivery | Joined lifecycle reproduction |
| --- | --- | --- | --- | --- |
| v2.1.11, `3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295` | 5/5 passed | 1/1 passed | 1/1 passed | 1/1 passed |
| `for-mobile-based-on-2.1.11`, `10c136466ae8da3e7954661acea6e6d915ee5318` | 5/5 passed | 1/1 passed | 1/1 passed | 1/1 passed |

## Scenario findings

### R1: Normal runtime reordering

Status: Reproduced

Sequence:

1. Start in the state where account data was sent but the BTC address was not received.
2. Deliver `C` first.
3. Confirm that `C` is queued and the state is unchanged.
4. Deliver `B` in the same process lifetime.

Observed result:

- `B` advances the state.
- The FSM immediately retries `C`.
- `C` advances the state to `SELLER_RECEIVED_FIAT_SENT_CONFIRMATION`.
- The event queue is empty.

Finding:

The message order `C, B` does not reproduce a stuck trade. Describing “out-of-order delivery” alone as the root cause is incomplete because arrival of the missing prerequisite is itself the later successful transition that drains the queue.

### R2: The prerequisite never reaches the FSM

Status: Reproduced

Sequence:

1. Deliver `C` while the trade is waiting for `B`.
2. Deliver the same `C` again.
3. Never deliver `B`.

Observed result:

- The state does not advance.
- `C` remains pending in the FSM event queue.
- Retrying `C` cannot replace its missing prerequisite.

Finding:

This is expected state-machine behaviour. It shows what a stuck state looks like, but it does not explain why `B` failed to reach the FSM. The root cause, if this matches production, must be in the missing prerequisite or in an earlier delivery boundary.

### R3: Restart occurs while a future event is queued

Status: Reproduced

Sequence:

1. Deliver `C` while waiting for `B`; `C` is queued.
2. Serialize and restore the trade, modelling a restart.
3. Deliver `B` to the restored protocol.

Observed result:

- The trade state survives the protobuf round-trip.
- The FSM event queue does not survive it.
- `B` advances the restored trade only to the state where the BTC address was received.
- The earlier `C` is no longer available to advance the trade further.

The persisted Trade message contains the state but no FSM event queue or processed-event set: [trade.proto](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/proto/trade.proto#L67-L86).

Finding:

Loss of an FSM queued event across restart is real. However, this reproduction makes restart lose the future event; it does not reproduce the reported observation that restart heals an already stuck trade. It must be treated as a separate restart-durability issue unless a production timeline connects it to the report.

### R4: Message processed between startup replay and listener registration

Status: Reproduced at the service boundary

The released `BisqEasyTradeService.initialize()` performs two separate actions:

1. read and replay the network service's processed-message set;
2. register the live confidential-message listener.

See [BisqEasyTradeService.initialize](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/java/bisq/trade/bisq_easy/BisqEasyTradeService.java#L136-L145).

The deterministic test forces a message to finish network processing after the replay read but before listener registration.

Observed result:

- the replay does not contain the message;
- no live listener is registered when the message is emitted;
- the listener is registered afterwards;
- the trade state remains unchanged.

Finding:

There is a real startup handoff window with a zero-delivery interleaving. It is a startup/lifecycle race, not ordinary runtime FSM reordering. The test proves the interleaving is possible from the API ordering; it does not prove that this interleaving caused issue #1622.

### R5: ACK and mailbox ownership are independent of domain application

Status: Code-confirmed; not reproduced as a full network test

The confidential-message service submits trade and ACK listeners asynchronously and returns network-processing success without awaiting them. Mailbox removal follows that success. The ACK listener independently sends an ACK for a valid ACK-requesting message.

Possible consequence:

- the trade listener can be absent, rejected, or fail;
- the ACK listener can still run;
- the peer can stop resending; and
- the mailbox copy can already be removed.

Finding:

This is an architectural delivery-ownership gap. It explains how a message could be considered delivered by the network without being applied by the trade FSM. It does not identify which callback, message, or trade was affected in the production report.

### R6: Runtime notification loss or listener failure

Status: Code-confirmed risk; not reproduced as the production issue

Listener callbacks use a shared executor. Its rejection handler removes the oldest queued task when the queue is full, and a second rejection drops the task. A listener exception is also asynchronous and cannot change the earlier successful return from confidential-message processing.

See [NetworkExecutors](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/NetworkExecutors.java#L51-L66) and [DiscardOldestPolicy](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/common/src/main/java/bisq/common/threading/DiscardOldestPolicy.java#L40-L60).

Finding:

Unlike R4, this risk can exist during normal runtime. Executor saturation appears to require an unusually large backlog, so its practical likelihood is unknown. No evidence currently connects it to issue #1622.

### R7: Message arrives before its trade protocol exists

Status: Code-confirmed; not exercised by the current characterization suite

If a Bisq Easy message reaches `BisqEasyTradeService` but no protocol exists for its trade ID, the message is stored in the service's in-memory `pendingMessages` set. It is retried when a later message is handled through an existing protocol.

See [BisqEasyTradeService message routing](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/java/bisq/trade/bisq_easy/BisqEasyTradeService.java#L238-L265).

Finding:

This is another ordering mechanism and another non-persisted collection. It is not the FSM event queue and should be investigated separately if logs show “Protocol with tradeId ... not found”.

### R8: Concurrent creation splits the persisted/UI model from the active protocol model

Status: Reproduced deterministically in one confidential-message-to-trade-service lifecycle; real transport sockets are not exercised

#### Production-reachable trigger

The unchanged networking and trade-service code provides a plausible path to two concurrent deliveries of the same take-offer request:

1. The network design sends messages over both supported privacy networks and intends the first arrival to be processed and the later arrival to be ignored.
2. A confidential send iterates all addresses in the receiver's transport map and sends the payload through each corresponding transport.
3. Every transport has its own `ServiceNode` and `ConfidentialMessageService`.
4. Duplicate filtering uses `processedEnvelopePayloadMessages`, which belongs to one `ConfidentialMessageService`. It prevents a duplicate within that service, but it is not shared across transports.
5. Each service submits its listener callback to the shared notification executor. Desktop config permits up to eight notification threads.
6. Consequently, the same logical take-offer payload can reach `BisqEasyTradeService.onMessage()` concurrently from two transports.

Relevant unchanged sources:

- [documented multi-transport delivery and intended duplicate suppression](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/docs/dev/network.md#L5-L17)
- [send through every receiver transport and register the listener on every service node](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/ServiceNodesByTransport.java#L198-L229)
- [one confidential-message service per service node](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/ServiceNode.java#L242-L247)
- [per-service duplicate set and asynchronous listener submission](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/p2p/services/confidential/ConfidentialMessageService.java#L510-L517)
- [multi-threaded notification executor](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/network/network/src/main/java/bisq/network/NetworkExecutors.java#L51-L66)

The intended “later ignored” property is therefore not enforced across the separate confidential-message services in the tested baseline.

The network characterization performs this sequence with unchanged production code:

1. create two `ConfidentialMessageService` instances, representing two transport-owned services;
2. encrypt and sign one payload for a shared receiver key;
3. pass that same encrypted message twice to service A and once to service B; and
4. register the same listener with both services.

Observed result:

- service A delivers the payload once and suppresses its own repeat;
- service B independently decrypts and delivers the same payload;
- the shared listener is called twice; and
- the test's synchronization barrier confirms that the two listener callbacks overlap.

This reproduces the duplicate/concurrent callback needed by the trade creation race at the confidential-service boundary. The payload used by that isolated network test is a built-in `Ping`; duplicate suppression is generic to all decrypted `EnvelopePayloadMessage` instances.

The joined lifecycle test then replaces the `Ping` with a valid, signed `BisqEasyTakeOfferRequest`. The request is encrypted once, passed to two independent real `ConfidentialMessageService` instances, decrypted and resolved by production code, and delivered by both services directly to the same real `BisqEasyTradeService`. The two listener callbacks overlap and enter the unsafe creation path. This connects the duplicate delivery and split-model behaviours in one test without mocking the confidential-message boundary. It still bypasses real `ServiceNode` instances, Tor/I2P sockets, mailbox storage, and ACK handling.

#### Unsafe creation sequence

For every incoming `BisqEasyTakeOfferRequest`, the maker path constructs a new trade object and then performs these separate operations:

1. check that no protocol exists for the trade ID;
2. check that the persisted store does not contain the trade ID;
3. add the new trade object to the store;
4. request persistence; and
5. create a protocol and put it in `tradeProtocolById`.

There is no lock or atomic compute around the complete check-and-create sequence. See [the unchanged maker factory](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/java/bisq/trade/bisq_easy/BisqEasyTradeService.java#L421-L459).

The deterministic tests force calls A and B to both complete the two guard reads before either completes creation. In the joined test, A and B are the two real confidential-message listener callbacks for the same take-offer request.

Observed result:

- both calls succeed for the same trade ID;
- A and B create distinct `BisqEasyTrade` objects and distinct protocol objects;
- the observable trade set keeps A because A and B are equal when inserted;
- the protocol map first stores A's protocol and is then overwritten with B's protocol;
- `findTrade(id)` therefore returns A, while `findProtocol(id).getTrade()` returns B; and
- the application now has two different sources of state for one trade ID.

The set behaviour follows from its concurrent-set backing: [ObservableSet](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/common/src/main/java/bisq/common/observable/collection/ObservableSet.java#L27-L50).

The desktop UI obtains the selected trade through `findTrade(id)` and observes that object's state, so A is the object presented to the user: [TradeStateController](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/apps/desktop/desktop/src/main/java/bisq/desktop/main/content/bisq_easy/open_trades/trade_state/TradeStateController.java#L119-L151).

#### User-visible stuck action and restart recovery

The test then puts A and B in `SELLER_RECEIVED_FIAT_SENT_CONFIRMATION` and invokes the real `sellerConfirmFiatReceipt(A)` service API, modelling the UI's “Payment received” action.

The service uses A only to obtain the trade ID. It then looks up the protocol by ID and handles the event on B: [event routing](https://github.com/bisq-network/bisq2/blob/3e0f0f8d69e2449a717d8a4bb404a9fa7dd9b295/trade/src/main/java/bisq/trade/bisq_easy/BisqEasyTradeService.java#L327-L353).

Observed result before restart:

- B advances to `SELLER_CONFIRMED_FIAT_RECEIPT`;
- A remains in `SELLER_RECEIVED_FIAT_SENT_CONFIRMATION`;
- the UI, which observes the stored trade A, appears not to advance; and
- persistence snapshots the store containing A, not the active protocol's B.

The joined test writes the real `BisqEasyTradeStore` to disk, creates a new `PersistenceService` and `BisqEasyTradeService` using the same application-data directory, reads the persisted store, and calls the real `initialize()`. Initialization rebuilds the protocol map from the persisted trade A.

Observed result after restart:

- the restored store and rebuilt protocol reference the same trade object; and
- retrying the identical “Payment received” action advances that object to `SELLER_CONFIRMED_FIAT_RECEIPT`.

The restart itself does not perform the missing transition. It repairs the object-identity split; the action succeeds when retried afterwards.

Finding:

This is the first reproduced mechanism in the unchanged baseline that explains both parts of the reported pattern: a normal trade action can be processed without the displayed/persisted trade advancing, and a restart can make a retry work. The same mechanism can affect “Mark payment sent” when the maker is the buyer, because all local actions route to the protocol by trade ID.

It remains a root-cause candidate rather than proof for issue #1622. The mechanism is now joined through real confidential-message and trade-service code, but actual multi-transport service nodes are not exercised and no affected production trace shows two model/protocol identities for one trade ID.

## What is established

1. The exact production incident is not yet reproducible from the available issue data.
2. Normal runtime `C, B` reordering is handled by the existing FSM and does not remain stuck.
3. Concurrent delivery of `B` and `C` through the real trade service converged correctly in all 64 controlled iterations.
4. A future event correctly cannot advance while its prerequisite is absent.
5. The FSM event queue is lost across restart in v2.1.11.
6. Startup replay followed by listener registration contains a reproducible message-loss window.
7. Network ACK/mailbox completion is not coupled to successful trade-domain application.
8. More than one in-memory holding area exists, and each has different ownership and retry behaviour.
9. Duplicate suppression is local to one confidential-message service: two services deliver the same encrypted payload twice and can invoke their shared listener concurrently.
10. Two concurrent same-ID maker creation calls can both pass the existing guards and create distinct model/protocol pairs.
11. The persisted/UI trade model can then differ from the model owned by the protocol map.
12. A real local trade action can advance only the protocol model, leaving the displayed/persisted model unchanged.
13. A valid signed take-offer request delivered by two real confidential-message services can trigger that split in one joined test.
14. Writing the store to disk, reading it after restart, and rebuilding protocols during initialization removes that identity split, after which retrying the action advances the displayed/persisted model.

## What is not established

1. We do not know which message or prerequisite was missing in the reported trades.
2. We do not know whether the reported controls failed locally, whether a remote notification was missing, or whether the UI merely reflected stale state.
3. We do not know whether the reported occurrence began during startup or normal runtime.
4. We do not know whether an ACK was sent or whether the sender still retained a resend copy.
5. We have not shown that persisting the FSM queue would reproduce or resolve the restart-recoverable production case.
6. We have not shown that the startup handoff window caused the production case.
7. We have not sent the request through real Tor/I2P `ServiceNode` instances or actual transport connections; the joined test invokes the two transport-owned service type instances directly.
8. We have not shown that duplicate creation occurred in any trade from issue #1622.
9. We have not reproduced a full two-peer failure using real transports, mailbox storage, ACKs, persistence, and application restart.

## Relationship between the observations

The current evidence supports the following separation:

| Observation | Interpretation |
| --- | --- |
| `C` arrives before `B`, then `B` arrives in the same runtime | Existing recovery works; no stuck trade. |
| `C` arrives, but `B` never reaches the FSM | Trade remains pending; the reason `B` is missing must be found. |
| Restart happens after `C` was queued and before `B` arrives | Queued `C` is lost; separate restart-durability defect. |
| Message lands between startup replay and listener registration | Message can miss the trade service; separate startup handoff defect. |
| Network accepts/ACKs a message but the trade callback does not apply it | Architectural ownership gap; possible startup or runtime loss, cause still unknown. |
| The same encrypted payload enters two confidential-message services | Reproduced twice-delivered, concurrently executing listener callbacks; cross-service deduplication is absent. |
| The same signed take-offer request enters both services and their callbacks overlap | Reproduced direct trigger of the same-ID trade-creation race in one joined lifecycle test. |
| Concurrent same-ID creation leaves store on A and protocol map on B | Reproduced split-model defect; actions can mutate B while the UI and persistence retain A. |
| Disk restore rebuilds the protocol from stored A, then the action is retried | Reproduced recovery mechanism; the retry now mutates the object observed by the UI. |
| Restart makes an affected production trade progress again | Reported recovery behaviour; the split-model defect is a close match but still needs a production or transport-level connection. |

## Required next evidence

Before selecting a fix for issue #1622, the most useful next evidence is either a trace of one affected production trade across both peers or a controlled two-peer reproduction through actual transport service nodes.

At minimum, capture:

- trade ID and both roles;
- core version and client type on both sides;
- timestamp and message ID for every relevant trade message;
- transport and mailbox/direct-delivery path;
- ACK status at the sender;
- confidential-message processing and listener-dispatch timestamps at the receiver;
- receiver FSM state before and after each message;
- identity hash and state of the trade returned by `findTrade(id)` and the trade owned by `findProtocol(id)`;
- whether the message entered `pendingMessages` or `eventQueue`;
- state, queue sizes, and delivery status immediately before shutdown; and
- the exact event that causes progress after restart.

A controlled two-node reproduction should then test these timelines separately:

1. `C, B` during uninterrupted runtime;
2. `C`, restart, then `B`;
3. `C` and `B` both received before restart;
4. message arrival during the startup replay/listener-registration boundary;
5. trade listener failure after network acceptance but before FSM application;
6. ACK delivered versus ACK deliberately withheld; and
7. mailbox delivery versus direct delivery;
8. the same take-offer request delivered concurrently over actual Tor/I2P service nodes;
9. identity of the stored trade, protocol-owned trade, and UI-observed trade after creation; and
10. a local action before and after serializing the store and restarting the receiver.

The test must first fail in the same way as the field report and must reproduce the same recovery after restarting. Without that equivalence, it is evidence for a different issue.

## Current conclusion

The available evidence does not support the broad statement that ordinary out-of-order Bisq Easy messages cause a permanent runtime stall. The tested concrete sequence recovers as soon as its prerequisite arrives, including when the two messages are delivered concurrently through the trade service.

The investigation has reproduced three distinct baseline defects or risks that must not be merged into one diagnosis:

1. the FSM queue loses pending future events across restart;
2. startup replay and listener registration have a zero-delivery handoff window; and
3. concurrent same-ID trade creation can split the persisted/UI model from the active protocol model.

The third defect is the closest match to the reported user experience. The joined test deterministically begins with one real signed take-offer request delivered by two confidential-message services, produces an action that is handled on an invisible model, leaves the disk-persisted/UI model stuck, and lets a retry work after disk restore rebuilds the protocol around the persisted model.

That makes cross-service duplicate creation the leading concrete hypothesis, not a confirmed production root cause. Its required pieces reproduce both independently and together. What remains is to remove the remaining harness boundary: deliver the request through actual Tor/I2P `ServiceNode` instances—or obtain equivalent production logs—and verify the same object-identity split and restart-plus-retry recovery. Only evidence matching the field conditions can establish that issue #1622 itself has been reproduced end to end.
