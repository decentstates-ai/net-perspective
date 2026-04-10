# Discussion on spec.md v1

## Core Design — Strong Points

The fundamental architecture is sound: signed documents, inductive computation of transitive relations, IPFS/IPNS for transport/content-addressing, size accountability pushed to users. The "emergent curation" model is elegant and the pull-not-push design is philosophically consistent.


## Issues Worth Discussing

### 1. `peered-by` in `user-info` — design contradiction

`user-info` is in a signed envelope (signed by the user). But `peered-by` is a list of peer user-ids that peer *this* user — information the user doesn't control and might not know at signing time. Either:
- The user can't sign this accurately (peers come and go), or
- The peer is re-signing on the user's behalf (requires user's private key)

This needs to be a separate peer-maintained document, not inside the user-signed envelope.

---

Comment by J:
The user decides before siging which peers they trust to peer them, yes the peers might decide not the peer the user, that's fine, let's rename this trused-peers in fact.


### 2. subject-glob and object-glob semantics undefined

They're declared as non-negative integers but there's no explanation of what the integers mean — what does `1` glob? `2`? The feature description ("include someone's subcategories under your categories, or collapse their subcategories") doesn't map to the field format. This is unimplementable as written.

---

Comment by J:
Subject glob means that you take someone elses contexts:
Alice (the subject) has context food.* that matches and subcontexts exposed by Bob (the object) in context food.*

Object glob means you are collapsing all their contexts into one of your contexts
Alice (the subject) has context food that contains all subcontexts exposed by Bob (the object) in any subcontexts matching food.*

### 3. Cancellation of batch update — atomicity

> "cancelling the previous run if it has gone for too long"

What's published when a run is cancelled mid-way? Some users get updated context-relations-deps, others don't. Is the final `user-info` publish atomic with all the per-user updates, or can you end up with a partially-updated peer state? Needs explicit semantics (e.g., only publish the index after all deps are computed).

---

Comment by J:
The steps presented in the spec are ordered so the final update, the user-info, updates the state atomically.
This means we can just cancel the DAG and any tasks running and not think about it.


### 4. direct-relations-context-addresses vs direct-relations-collection-context-address

In `context-relations-deps-hop`, both fields exist with similar names and both are arrays of multihash. The difference between them isn't explained. One seems to be individual direct-relations documents, the other a "collection" — but "collection" isn't defined as a document type anywhere.

---
Comment by J:
One is a list of the content-addresses of direct-relations, the other is a content address of a compressed file containing all the direct relations.

### 5. Timestamp inconsistency

The intro says "every five minutes peers servers calculate data dependency sets" — the Mechanics section says "every 10m". Pick one.

---

Comment by J:
Let's go with 10m.


### 6. No key revocation

If a private key is compromised, there's no mechanism to signal a transition to a new key. The previous `prsp` system had `valid-until` on publications — the new spec drops this without replacement. Worth deliberate decision: is the IPNS record itself the revocation mechanism (user stops updating it)?

---

Comment by J:
Let's defer this to a later spec, it isn't important at this stage. Additionally "valid-until" doesn't mitigate compromised keys as the attacker can republish with a new valid-until.
The answer will likely be social, where you contact users that use your key, and just create a new key.

### 7. transitive-depth vs 1-10 hop limit

`direct-relations-rel-user/transitive-depth` defaults to 2 and is unbounded. But `context-relations-deps-hop/hop` is constrained to integers 1-10. What happens when a user sets `transitive-depth > 10`? The spec needs an explicit maximum or the deps format needs to accommodate it.

---

Comment by J:
Ok, let's bind to 10.


### 8. direct-relations/contact-* fields

Email, Signal username, phone number are PII published in a signed document on a public content-addressed store. They're also structurally awkward — they change independently of your relations but force a new direct-relations document. Consider whether contact info belongs in `user-info` or a separate document. Also: there's no removal mechanism other than publishing a new document without them.

---

Comment by J:
This is not so important, let's leave until later.

### 9. "should this be merged?" — unsettled structure

The spec itself notes `(should this be merged into the above)` for `direct-relations`. That indecision leaks into implementation. Decide: does the peer store one IPNS record pointing to a single document with both user-info and direct-relations, or two separately-updateable records? The tradeoff is update granularity vs. complexity.

---

Comment by J:
This is not so important, let's leave until later.

### 10. ML-DSA-44 size implications

The choice is noted with "seems like" — worth making this deliberate. ML-DSA-44 signatures are ~2420 bytes and public keys ~1312 bytes, vs Ed25519's 64 and 32 bytes. Given the spec's focus on "weight budgets" and size tracking, every document gets substantially heavier. This is fine if post-quantum is a goal, but should be an explicit decision, not tentative.

---

Comment by J:
The year is 2026 and PQ is now an assumed requirement, no need to mention.


## Things Not Addressed

- **Bootstrap / peer discovery**: How does a new user find their first peer? How do peers discover each other's existence?

---

Comment by J:
IPFS contains bootstrap/peer-discovery.

- **Context path limits**: No specified max depth or segment length for context paths.

---
Comment by J:
Let's leave for later.

- **Conflict resolution**: Two direct-relations documents with the same or close timestamps — which wins? Is the peer's acceptance of "latest wins by timestamp-ns" the rule?
---
Comment by J:
Latest wins.
- **Versioning on all documents**: Only `direct-relations` has a version field. Missing on `user-info`, `context-relations-deps`, etc.
---
Comment by J:
That's true, please add them in.
- **URL length constraints for UI**: "Store state in the URL" hits browser limits (2000–8000 chars) with complex state.

---
Comment by J:
She'll be right


## Minor

- Context path regex `[a-z][a-z0-9\-]` — missing a `*` or `+` quantifier; as written it matches only a single character after the leading `[a-z]`.
---
Comment by J:
That is a typo, thanks.

- The "collect" operation described in the intro isn't named as an action in the Mechanics section — only `submit` and `fetch` are defined for user-client. What's the call that produces the rendered view?
---
Comment by J:
Collect is calculated one the client side by scanning the direct-relations, mechanics are more about the bigger picture.
