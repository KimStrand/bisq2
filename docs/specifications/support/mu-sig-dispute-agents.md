# Dispute agents of MuSig trades

This specification defines when a node processes the requests of the dispute agents of MuSig trades: the mediation
requests which a MuSig mediator receives, and the arbitration requests which a MuSig arbitrator receives.

## Rules

1. A node processes MuSig mediation and arbitration requests only in developer mode. Outside developer mode, the services
   of the MuSig mediator and the MuSig arbitrator neither listen for these requests nor process the requests which the
   node received before the services started.
2. Rule 1 applies to every node, also to a node which holds the identity of an authorized mediator or arbitrator.
3. A MuSig mediator or arbitrator accepts a request only when:
   - the requester is not banned;
   - the requester and the peer are two different profiles and the two parties of the contract, compared by ID;
   - the receiver is the mediator or arbitrator of the contract, compared by ID, and neither party has its key;
   - every forwarded chat message has the trade ID and the channel of the trade, and the requester or the peer sent
     it.

   It ignores every other request.

## Reasons

- MuSig trading runs only in developer mode, so no honest trader outside developer mode sends such a request.
- Rule 3, IDs: the contract holds the network IDs from when the offer was published and taken, and the addresses of a
  trader can change since then (see [identity by key](../user/identity-by-key.md)). The requester supplies both the
  contract, which carries no signatures, and the profiles, so a comparison of the addresses would bind them to nothing
  trustworthy.
- Rule 3, two profiles: a trade and the channel of its case have two traders.
- Rule 3, the key of the receiver: the requester can name the profile of the mediator or arbitrator as a trader in the
  contract. Chat messages of that profile would then pass as messages of a trader, although they claim to come from
  the mediator or arbitrator.
- The checks of these requests are not complete. An arbitrator verifies the mediation result with the key of the
  mediator which the contract of the requester names. It does not check that this mediator is an authorized bonded
  mediator, and the other trader does not confirm the contract. Any peer can therefore create a contract with its own
  traders and mediator, and a node with the identity of an arbitrator would persist the case, create a channel and send
  messages.

## Known limitations

- **Incomplete checks of the requests in developer mode.** In developer mode an arbitrator still accepts a mediation
  result of a mediator which the requester names, without a check of its role, and without a confirmation of the
  contract by the other trader.
  - Why it is not removed now: MuSig is not active outside developer mode, so the gap affects no user of a release.
  - What removes it: the arbitrator requires that the mediator of the contract is an authorized bonded mediator, other
    than the traders and the arbitrator, and the other trader confirms the hash of the contract.
  - Who decides: the developers, before MuSig leaves developer mode.
