---
"hephaestus": minor
---

Instance administrators can preview, export and erase one person's data using an account ID or exact
provider identities, including people who never created an account. Names, logins and email addresses
are not used to match. JSON exports and erasure use the same preview scope, and erasure can resume from
its last completed store. Posted provider feedback must be removed with the operator runbook first.
Completed audit receipts retain operational facts and counts, not the erased content.

Shared records retain other people's attribution. Erasure clears the person's typed approval,
withdrawal, invalidation and restoration attribution while keeping the event and its time.

Erased provider identities cannot create a new linked account or start another practice review.
Native Slack conversations and Outline documents are included even when the resulting observations
are about a different participant. Shared Slack conversations retain the other participants' work.

Person exports include authored milestones and commit file-change copies. Applying another
developer's commit removes only the person's committer attribution, not that developer's work.
Heph exports visible conversation text and usage facts, not hidden reasoning, tool payloads or
internal runtime journals that can contain credentials or another person's copied profile.
Shared Outline document bodies and co-authored commit content are included through exact native
attribution. Erasure clears those local copies while preserving the other authors' attribution.


**Operators:** On upgrade, Heph's hidden conversation memory resets once. The migration clears old
runtime journals because they have no complete exact-person provenance. Every visible conversation
message, title and time stays. Take and verify a backup before the upgrade if your retention policy
requires those old journals; the cleared hidden memory cannot be restored from the upgraded database.

Your past conversations stay visible. Heph starts with fresh hidden memory after this upgrade, so you
may need to repeat context from an earlier conversation.
