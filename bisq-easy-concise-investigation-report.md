# Bisq Easy stuck-trade investigation — concise report

Date: 2026-08-13

## One-sentence summary

The released FSM handles ordinary message reordering, but we reproduced a different defect where duplicate take-offer processing creates two trade objects; this closely matches the restart-recoverable symptom but is not yet proven to have caused the production incident.

## Goal

Understand exactly why a trade can appear stuck and work again after restarting the core process. Confirmed but separate problems must remain separate until evidence connects one of them to the reported incident.

## Tested baseline

All tests used unchanged code from desktop v2.1.11 and `for-mobile-based-on-2.1.11`. The same results were observed on both.

## Topic 1: Ordinary message reordering

### Certain

A message that arrives before its prerequisite is queued. When the prerequisite arrives and changes the state, the FSM immediately retries the queued message. This worked in deterministic and concurrent tests.

### Meaning

Ordinary reordering does not cause a permanent stall if the prerequisite later arrives. If the prerequisite never arrives, the dependent message remains pending; we do not know whether that happened in production.

## Topic 2: FSM queue and restart

### Certain

Queued FSM events are not persisted in v2.1.11 and are lost on restart.

### Meaning

This is a separate durability defect. Its reproduced effect is lost work after restart, so it does not by itself explain why restart makes an action work.

## Topic 3: Startup message handoff

### Certain

The released trade service reads previously processed messages and then registers its live listener. A message can arrive between those operations and miss both paths. This startup race was reproduced.

### Meaning

This is a separate startup message-loss defect. Nothing currently connects it to the reported incident.

## Topic 4: Network ACK versus trade application

### Certain

Network acceptance, mailbox removal, and ACK handling do not wait for successful application by the trade FSM. An ACK proves network acceptance, not a trade-state change.

### Meaning

This is a separate delivery-ownership gap. It might matter at startup or runtime, but no evidence connects it to the reported incident.

## Topic 5: Duplicate creation and split trade objects

### Certain

A joined deterministic test reproduced this sequence with unchanged release code:

1. Two independent confidential-message services deliver the same valid, signed take-offer request.
2. Their callbacks overlap and both pass the separate “trade does not exist” checks.
3. Two objects are created for the same trade ID.
4. The store and UI-facing lookup retain one object; the active protocol uses the other.
5. A real user action changes only the protocol-owned object, so the stored/UI-facing object appears stuck.
6. Disk persistence saves the unchanged stored object.
7. Restart rebuilds the protocol around that stored object, after which retrying the action works.

The joined test passed on both tested baselines.

### Meaning

This is a confirmed defect and the closest reproduced match to the reported symptom. It is the leading hypothesis, not a confirmed production root cause.

## What we do not know

We do not know:

- whether affected production trades contained two trade objects;
- whether one take-offer request actually arrived concurrently over Tor and I2P;
- whether the incident started during startup or normal runtime;
- which exact action, role, message, and FSM state were involved;
- whether an ACK was sent or a mailbox entry was removed; or
- whether another defect produced the same visible symptom.

The joined test deliberately forces the timing race. It uses real confidential-message, trade-service, FSM, and disk-persistence code, but not actual Tor/I2P connections or a complete two-peer mailbox and ACK lifecycle.

## Do not combine these conclusions

- Reordering is not the same as a missing prerequisite.
- FSM queue loss is not the same as startup message loss.
- Network acceptance is not the same as trade application.
- A reproduced split-object defect is not proof that production experienced it.

## Next evidence needed

Confirm the production cause by either:

1. reproducing the split through actual Tor/I2P service nodes in a controlled two-peer test; or
2. capturing an affected trade where the stored trade and protocol-owned trade are different objects.

Until then, the accurate conclusion is: **the split-object defect is real and matches the symptom, but its connection to the production incident remains unproven.**

## Supporting material

- Detailed findings: `bisq-easy-existing-behaviour-and-reproduction.md`
- Joined reproduction: `review-artifacts/existing-behaviour/BisqEasyCrossTransportLifecycleEvidenceTest.java`
