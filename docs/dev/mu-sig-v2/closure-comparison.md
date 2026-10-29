# MuSig V1 / V2 closure and timeout comparison

Start with the [architecture overview](architecture-overview.md). This document owns the closure-specific
V1/V2 comparison; general save/send/recovery rules live in [processing rules](processing-rules.md).

This comparison describes the Java code in the current working tree.
The maker and taker variants have the same closure rules for their respective buyer or seller role.
The backend constraints below were checked against the Rust source at commit `7737ccc594433e144d780589bd8ecf6b4079b999`.

Agreed V2 behavior: seller force closure is available only from `PaymentReceiptConfirmed`. Signing a
custom payout starts no timer and does not permit seller force closure. Recovery if the peer does not
complete the mediated payout remains an open decision in [TODO.md](todo.md).

## Settlement paths

| Trader and path | V1 | V2 |
| --- | --- | --- |
| Buyer, normal settlement | Starts a 24-hour timer when payment is initiated. Declares `BUYER_INITIATED_PAYMENT` → `BUYER_FORCE_CLOSED_TRADE` on timeout, but the handler is unfinished; see below. | Saves a 24-hour deadline on payment initiation. Timeout logs locally, clears the deadline and stays in `PaymentInitiated`; real recovery and a force-closed state remain unfinished. Message F can still close the trade afterward. |
| Seller, normal settlement | Starts a 24-hour timer when payment receipt is confirmed. Timeout from `SELLER_CONFIRMED_PAYMENT_RECEIPT` calls `closeTrade` with only the trade ID, then enters `SELLER_FORCE_CLOSED_TRADE` on success. | Same trigger and duration. Timeout from `PaymentReceiptConfirmed` makes the same RPC request and enters `SellerForceClosedTrade` on success. |
| Buyer, mediated settlement | Signing the custom payout starts no new timer. A timer from earlier payment initiation is not cancelled by signing, but there is no timeout transition from `CUSTOM_PAYOUT_SIGNED`. | Signing the custom payout starts no timer and cancels an earlier payment timer. There is no force-closure transition from `CustomPayoutSigned`. |
| Seller, mediated settlement | Signing the custom payout starts no timer. There is no timeout transition from `CUSTOM_PAYOUT_SIGNED`. The normal seller timer cannot already be running on the allowed path into that state. | Signing starts no timer. There is no force-closure transition from `CustomPayoutSigned`, matching the backend restriction. An explicitly supplied timeout is discarded without calling the backend. |

Opening mediation, receiving its result, or learning the peer's acceptance does not itself start a
closing timer. Neither role starts a timer when signing the mediated payout in V2.
After local signing, both versions prevent normal cooperative closure. Successful mediated closure
uses `customCloseTrade` with the peer's PSBT and requires a nonempty custom payout transaction response.

Sources: V1's [buyer payment handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/buyer/PaymentInitiatedEventHandler.java),
[seller payment handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/seller/PaymentReceiptConfirmedEventHandler.java),
[acceptance handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/mediation/MediationResultAcceptedEventHandler.java),
[seller timeout handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/seller/SellersCloseTradeTimeoutEventHandler.java), and
[custom finalization handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/mediation/CustomPayoutFinalizationEventHandler.java).
V2's [buyer payment handler](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/buyer/PaymentInitiatedEventHandler.java),
[seller payment handler](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/seller/PaymentReceiptConfirmedEventHandler.java),
[signing handler](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/MediationResultByMyselfAcceptedEventHandler.java),
[seller timeout handler](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/seller/SellersCloseTradeTimeoutEventHandler.java), and
[custom finalization handler](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/CustomPayoutFinalizationHandler.java).

The source and target states are declared in these four pairs of protocol definitions:

| Role | V1 | V2 |
| --- | --- | --- |
| Buyer as maker | [MuSigBuyerAsMakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig/protocol/MuSigBuyerAsMakerProtocol.java) | [BuyerAsMakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/buyer_as_maker/BuyerAsMakerProtocol.java) |
| Buyer as taker | [MuSigBuyerAsTakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig/protocol/MuSigBuyerAsTakerProtocol.java) | [BuyerAsTakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/buyer_as_taker/BuyerAsTakerProtocol.java) |
| Seller as maker | [MuSigSellerAsMakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig/protocol/MuSigSellerAsMakerProtocol.java) | [SellerAsMakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/seller_as_maker/SellerAsMakerProtocol.java) |
| Seller as taker | [MuSigSellerAsTakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig/protocol/MuSigSellerAsTakerProtocol.java) | [SellerAsTakerProtocol](../../../trade/src/main/java/bisq/trade/mu_sig_v2/protocol/execution/seller_as_taker/SellerAsTakerProtocol.java) |

## Timer lifecycle

| Situation | V1 | V2 |
| --- | --- | --- |
| Timer representation and start | An in-memory `Scheduler` waits 24 hours. Payment handlers start it during processing, before their model commit and outgoing message. | Buyer payment initiation and seller payment-receipt confirmation return a `ScheduledEvent` with the event, ID and absolute deadline: the handler's clock time plus 24 hours. The deadline is checkpointed with the state before effects are dispatched. |
| Buyer closes normally on message F | Stops the buyer timer before calling the backend. | Successful closure emits cancellation, which is persisted before dispatch. |
| Seller closes normally on message G | Stops the seller timer before calling the backend. | Successful closure emits cancellation, which is persisted before dispatch. |
| Mediated payout completes | Stops the trade's timer for either role after successful backend finalization. This also stops the buyer's earlier payment timer if one exists. | No additional cancellation at finalization: buyer custom signing already cancels an earlier payment timer, and the seller cannot have started its timer on an allowed path to custom signing. |
| A handler or peer reports execution failure | Error handlers do not explicitly stop a remaining timer. A later timeout is ignored once execution is final. | `Failed` and `FailedAtPeer` transitions emit cancellation. Failure handling and persistence must succeed for the cancellation to be applied. |
| Restart before the deadline | Live timers and their deadlines are not persisted. Startup recreates protocols but does not rearm closing timers from the saved state. | Recovery restores the saved absolute deadline and schedules the remaining wait. It does not give the trader another 24 hours. |
| Restart after the deadline | There is no saved live deadline to detect as overdue. | Recovery schedules a zero-delay timer that queues an input. The lane processes it after that trade's recovery succeeds; it is not a synchronous close during loading. |
| Timeout races with successful closure | Event handling is serialized. A callback reaching an already-final state is ignored. | Cancellation invalidates even an already-queued scheduler callback. The execution graph also discards a timeout after closure. A successfully processed closing path makes an incompatible later closing input unreachable. |
| Outgoing message handoff throws immediately during active execution | The timer was started before the send. The exception enters FSM failure handling; a later timeout is ignored after execution becomes final. | Keep the committed execution state, pending message and closing deadline. Report the send exception; a later eligible input or restart recovery retries the saved message without repeating its handler. No processing failure or error report is created. |
| Buyer backend closure succeeds, then sending message G throws immediately | Message G is sent before setting `BUYER_CLOSED_TRADE`; the exception enters FSM failure handling. | Keep the saved local `TradeClosed` state and pending message G. Report the send failure to the caller; replay the message without repeating backend closure. |
| Delivery fails asynchronously after the send call returns | The payment handler does not await the send future. The timer continues; delivery handling belongs to the network layer. | The adapter has accepted delivery responsibility. The committed deadline remains; delivery handling belongs to the network layer. |

Agreed: keep V2's cancellation order. Buyer and seller closure call the backend first, then persist the terminal
execution state and timer cancellation before stopping the running timer. Buyer custom signing also cancels
an earlier payment timer with its successful transition. `Failed` and `FailedAtPeer`
also cancel through this persisted-effect path. Under the current terminal-failure policy, a backend
failure does not leave the timer running as an automatic recovery attempt, provided failure handling
and persistence succeed.

The trade's lane processes inputs serially. A timeout expiring during the backend call waits in the
trade's queue; cancellation invalidates that queued callback after closure or failure is persisted.
These intentional differences from V1 keep the saved execution state and timer status consistent.

Immediate send failure preserves V2's saved execution state, deadline and outgoing message, including after
buyer closure. This differs from V1's failure transition after an immediate throw. Delivery failures after a
normal return remain the network layer's responsibility in both versions. The complete rules and acknowledged
handoff crash window live under [outgoing work](processing-rules.md#outgoing-messages-and-logs).

## Backend constraints and implementation gaps

**V1's buyer force-closure edge is not a working fallback.**
The [handler](../../../trade/src/main/java/bisq/trade/mu_sig/events/buyer/BuyersCloseTradeTimeoutEventHandler.java) has its swap-transaction
retrieval and backend closure code commented out. Its close response remains null. The subsequent
commit calls [MuSigTradeParty.setMyCloseTradeResponse](../../../trade/src/main/java/bisq/trade/mu_sig/MuSigTradeParty.java), which uses
`Optional.of` and therefore throws. The FSM enters its error-handling path instead of successfully
reaching `BUYER_FORCE_CLOSED_TRADE`. This conclusion follows from the code; no live buyer recovery was
performed for this comparison. Porting the declared edge would not provide the missing recovery.

**The backend forbids seller force closure after custom signing.**
The seller rule in [state.rs](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/protocol/src/state.rs#L87-L96)
rejects `CustomPayoutSigned` to `TradeClosed(Forced)`.
[State updates](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/protocol.rs#L224-L237)
enforce that rule and report an illegal transition. V2 therefore removes that seller transition from
both role definitions and restricts the timeout handler's source type to `PaymentReceiptConfirmed`.
This replaces the earlier assumption that normal seller force closure could be reused after custom signing.

The [CloseTrade RPC](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/server.rs#L194-L218)
selects forced closure when the seller supplies only the trade ID. It requires the signed normal swap
transaction; the broadcast itself remains a TODO in that implementation. Mediated finalization uses
the separate `CustomCloseTrade` RPC with the peer's signed custom-payout PSBT.

**Buyer recovery has different prerequisites.**
The backend [permits buyer force closure after custom signing](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/protocol/src/state.rs#L188-L199).
Its RPC path accepts the seller's signed normal swap transaction and recovers the seller's key share
from it. A timeout alone does not supply that transaction. This recovery path remains unimplemented in V2.

The checked backend does not yet provide a complete observation path for this recovery:

- [SubscribeTxConfirmationStatus](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/server.rs#L159-L167)
  currently returns a mock deposit-confirmation stream, not the transaction spending the deposit output.
- The [wallet confidence API](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/main/proto/wallet.proto#L46-L55)
  can return raw transaction bytes, but requires a known transaction ID. Its
  [confidence entries](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/wallet.rs#L126-L139)
  come from wallet transactions. Only the seller
  [computes the unsigned swap transaction](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/protocol.rs#L437-L442)
  during setup, so this API alone does not supply buyer recovery observation.
- The backend's [buyer recovery example](https://github.com/bisq-network/bisq-musig/blob/7737ccc594433e144d780589bd8ecf6b4079b999/rpc/src/main/java/bisq/TradeProtocolClient.java#L335-L350)
  passes the swap transaction directly from the seller's RPC response into the buyer's request.
  It does not demonstrate discovery on the Bitcoin network.

Buyer recovery stays deferred until observation, validation and confirmation requirements, and
observation startup and restart behavior are agreed. These dependencies are recorded in [TODO.md](todo.md).

V2's buyer timeout currently performs only a self-transition in `PaymentInitiated`. It logs the missing
recovery and cancels the deadline without calling the backend or sending a peer message. Its type is
recorded as processed, so it will not run again after successful persistence. Implementing actual recovery
later needs an explicit plan for trades whose placeholder timeout was already consumed.

## Verification entry points

- [MuSigTradeServicePairTest](../../../trade/src/test/java/bisq/trade/mu_sig_v2/MuSigTradeServicePairTest.java):
  normal seller force closure, both buyer roles' placeholder timeout across restart, no seller timer after custom
  signing, cancelled callbacks and restart between mediation acceptances. The backend is mocked.
- [MuSigTradePersistenceTest](../../../trade/src/test/java/bisq/trade/mu_sig_v2/MuSigTradePersistenceTest.java):
  overdue timeout delivery and saved cancellation preventing another closure after a second restart.
- [InputSchedulerTest](../../../common/src/test/java/bisq/common/processing/InputSchedulerTest.java):
  remaining time, overdue delivery, cancellation and replacement. Protocol tests cover state reachability.

The unresolved buyer and seller recovery mechanisms, automation and migration of consumed placeholder timers
are tracked once in [TODO: execution and disputes](todo.md#execution-and-disputes). Further changes require
agreement first; the backend findings above are pinned-source analysis, not evidence of a live recovery test.
