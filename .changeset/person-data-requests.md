---
"hephaestus": minor
---

Instance administrators can preview, export and erase one person's data using an account ID or exact
provider identities, including people who never created an account. Names, logins and email addresses
are not used to match. JSON exports and erasure use the same preview scope, and erasure can resume from
its last completed store. Posted provider feedback must be removed with the operator runbook first.
Completed audit receipts retain operational facts and counts, not the erased content.
The selected scope stays visible while an export downloads or erasure starts. The controls prevent
a second action until the current action and its status refresh finish.

Shared records retain other people's attribution. Erasure clears the person's typed approval,
withdrawal, invalidation and restoration attribution while keeping the event and its time.

Erased provider identities cannot create a new linked account or start another practice review.
Activity reconciliation skips erased authors while keeping other authors in the same batch.
Export and erasure include unattributed activity for the person's exactly linked authored work.
AI source queries skip re-mirrored Slack messages and Outline documents associated with erased
native identities. A Slack control applies only to its exact team.
Native Slack conversations and Outline documents are included even when the resulting observations
are about a different participant. Shared Slack conversations retain the other participants' work.

Person exports include authored milestones and commit file-change copies. Applying another
developer's commit removes only the person's committer attribution, not that developer's work.
Feedback copied into another person's Heph conversation is exported through its exact delivery
reference. Erasure replaces that copy and resets the thread's hidden memory while keeping the other
person's replies and conversation times.
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

Person exports list repositories whose history may contain the person's commits. Git history is
not rewritten: repository mirrors and short-lived job folders cache the organization's upstream
repository. The repository owner must remove Git authorship at the source. Hephaestus removes its
own person records and derived data and removes affected attempt folders during erasure.

Erasure waits for the mounted evidence store to confirm deletion of all affected attempts, including
failed rendering folders. An active runtime must release its folder before deletion is acknowledged.
If a worker volume is offline, erasure stops with a resumable failure; an empty server folder does
not count as deletion. Capture admission is serialized with erasure across runtime roles, without
changing the existing job-folder retention period.

Each completed store step records its count, completion time and exact acting account. A different
administrator can resume a failed request without changing the earlier steps' attribution. Erasing
an administrator clears their step attribution, not the counts or times. Each new step verifies that
the acting administrator still has active instance-admin access.

Workspace purge also queues removal of mounted review copies. Offline worker volumes retain a
durable removal request until they return. Their identity metadata is cleared only after confirmed
folder deletion; deleting a database job alone does not count as removing its files.

Person requests include existing provider records that differ only in the spelling of the same
network origin, such as host case or a default HTTPS port. Native identities still match exactly.
A link to another account on any equivalent provider record stops the request.

Erasure also includes observations and feedback derived from copied evidence about the person, even
when the review's primary developer is someone else. Confirmed folder removal does not discard that
exact provenance before the counted erasure step. Unrelated reviews and the other developer's profile
and replies stay.

A provider instance added after erasure cannot reset a processing control by changing host case or
adding a default port. Native identity write locks use the same exact provider origin, so an admitted
write on an equivalent provider record must finish before erasure checks its scope. Different
providers, non-default ports, native users and Slack workspaces remain separate identities.

Slack and Outline connections register their provider instance without requiring an account login.
Connecting another workspace to the same exact instance carries existing processing controls forward,
including ranked document search. Changing a connected source to a different instance requires a new
connection; a URL spelling change does not change the instance.

The preview includes unconfirmed provider writes and legacy review comment references as well
as confirmed comments. Inspect both
summary and inline feedback at each listed reviewed-work locator before erasure. If the delivery
state changes after inspection, make a new preview and check the provider again; the application
will not accept the old confirmation. An unconfirmed write with no exact locator stops the request.

Review-package delivery waits for erasure admission and rejects revoked review jobs. Erasure
waits for an already active provider request to record its outcome before it removes local data.

Heph refuses new conversations and does not prepare a runtime for an erased identity, even if
provider sync recreates its profile. The refusal applies to web and Slack entry points and is
separate from Your AI choice.

Heph reads your current profile and work context for each turn instead of keeping formatted
personal context in a five-minute cache. This prevents those cached copies from outliving erasure.
