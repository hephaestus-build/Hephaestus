# ADR 0164: Staff see no person's private content, and support starts with the person

**Status:** Accepted
**Date:** 2026-11-26
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decisions 2, 3, 7 and 9. [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md) decision 8.

**Builds on:** [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md), [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md)

## Context

[ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decided that no staff route can tell anything about one person.
Main still had four staff paths to a person's private content:

- **View as user.** An instance admin read a member's pages as that member, with a recorded reason.
- **The person-data export.** An instance admin downloaded a person's data, their conversation included.
- **Per-person results for admins.** A workspace owner could name the developer of every result for the workspace admins.
- **A disagreement.** The developer's explanation stayed readable to workspace admins with no end.

On 2026-11-26 the maintainer decided that staff do not see people's private content.
Operators must still run the server, find a failed turn and answer a data-subject request.
The real constraint is that an operator can find and fix a failure without reading what a person said.

## Decision drivers

- No staff route returns a person's words, Hephaestus's words to them, or their private results.
- An operator can still find one failed turn in the logs and metrics.
- A person can show their problem to support, and stop showing it.
- A data-subject request still gets its records, and its erasure.
- A deleted or erased message never survives in a copy.

## Considered options

1. **Keep the audited staff reads.** The audit row records a read after it happened. The person cannot stop the read, so it stays a monitoring path with a log.
2. **Remove every staff read and add nothing.** Support cannot debug a failed turn. Operators answer each access request by hand from the database.
3. **A support snapshot that copies the text** ([ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md) decision 8). The copy needs its own retention, erasure, workspace purge and definer function. A message that the person deletes survives in the copy until it expires.
4. **Remove the staff reads, keep content-free diagnostics and item-scoped acts, and let the person start a share that copies nothing (chosen).**

## Decision

Option 4.

1. **View as user is removed.** Its routes, headers, filter, rate limit, step-up hooks and pages go.
   - A request that still carries `X-User-View-Workspace` or `X-User-View-Account` is refused. An open tab of the old page cannot show an admin's own pages under another person's name.
   - Earlier `USER_VIEW` audit rows stay. They are the record of the reads that happened.
2. **An instance admin cannot download a person's data.** The preview and the erasure of a person-data request stay.
   - The admin delivers the frozen scope to the person's own data exports. Only the person downloads it, until the preview expires.
   - A download holds only the preview's records that the person's current scope still selects, read in one snapshot. A record that left the scope, such as a document that no longer names the person, is not in it. What the person does after the preview does not join it.
   - Delivery needs the person's account. It refuses a scope with an identity that the account does not hold, because that identity can be another person's.
   - The delivery ships in #5390. Until then, no console path hands over the records, and the operator answers an access request outside Hephaestus.
3. **Per-person results for admins is removed.** The setting, its column and every person field and person filter of the staff review routes go.
   - Each workspace that had it on gets one configuration audit row.
   - The copy on the work still names its developer, as the work itself does ([ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decision 12).
4. **Item-scoped staff acts stay.** An approval works as ADR 0125 decision 3 says. A correction shows the explanation of a disagreement for 30 days after the person wrote it. After that, staff see only that a disagreement exists and when.
5. **Support starts with the person.** A person shares one of their own conversation turns with one active instance admin.
   - The preview shows exactly what the recipient reads: the turn's diagnostics, and its messages only if the person adds them.
   - The person confirms the digest of the previewed messages. A changed message refuses the share.
   - A share runs for 7 days. The person can revoke it at any time.
   - The person's list shows every active share and the 50 latest ended ones, each with its recipient, its state, its read count and its last read.
6. **A share copies nothing.** It stores ids, times and the digest.
   - A read renders the turn from its current rows. It shows the messages only while they match the digest. A deleted, erased or changed message is withheld.
   - The read counts in the statement that checks the recipient, the revocation and the expiry.
   - A read needs no recent sign-in. The person started the share, chose its one recipient and sees each read.
7. **Diagnostics hold no content.** A turn's diagnostics are its id, run id, surface, status, attempt, failure reason as a closed kind, failed model call and its provider status, and its times. With the run id, an operator finds the turn in the logs and metrics. No staff route lists a person's turns.
8. **One staff route returns a person's words:** the read of a support share, by its one recipient.

### What this changes in other ADRs

- [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decision 2: the audit rows of View as user stay.
- ADR 0125 decision 3: the 30 days of a correction count from the person's disagreement.
- ADR 0125 decision 7: the delivery goes into the person's own data exports.
- ADR 0125 decision 9: the support snapshot is a share of one turn that copies no text.
- [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md) decision 8: `support_share_mark_read` is not needed, so the list has four definer functions. The share holds no person content, and its read counts with one update of the share.

## Consequences

- Good: no staff route returns a person's private content. A generated test sweeps every staff route.
- Good: a deleted or erased message disappears from every share with no extra step.
- Good: an operator debugs a shared turn from its run id, without its text.
- Bad: an admin sees a person's problem only after the person shares it.
- Bad: an answer can quote work that the recipient cannot read on the provider. The person sees the exact preview and can share the diagnostics only.
- Bad: a share of a turn that still runs can lose its text when the answer grows.
- Bad: no person-data store names the support shares yet. The account purge deletes them. #5364 adds them to the erasure inventory over two releases, because a server of the previous release refuses an inventory with a store that it does not know.
- Bad: until #5390 ships, the operator answers an access request outside Hephaestus.
- Bad: a person without an account cannot get a delivery. The operator answers that request from the server's storage.
- Bad: the erasure preview still counts rows per store for one person. The operator needs the counts to confirm the scope of the request. The preview is audited and needs a recent sign-in.

## Confirmation

- `StaffRoutesReturnNoPrivateContentTest` fails when a staff route returns a type that a person's own conversation, Practice profile or support share routes return, or a `@PrivateContent` type. The support share read is its one exception. It also fails when a class named for View as user, Per-person results for admins or impersonation exists.
- `StepUpGateIntegrationTest.shouldRefuseARequestThatStillCarriesTheRemovedUserViewHeaders` fails when the old headers reach a page.
- `SupportShareIntegrationTest` fails when a share serves after its expiry or revocation, serves another recipient, shows changed or deleted text, or does not count a read.
- `AccountErasureIntegrationTest` fails when an erasure leaves a support share of the person, as its sharer or its recipient. #5390 adds the proofs of the delivery.
- `FeedbackReviewControllerIntegrationTest` and `PracticeReviewOutputControllerIntegrationTest` fail when a disagreement's explanation shows after 30 days.
- `PerPersonResultsRemovalMigrationTest` fails when the removal does not record one audit row for each workspace that had the setting on.

## Revisit trigger

- Support needs more than one turn to understand a problem.
- The conversation tables get row-level security ([ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md)). The share read then needs a definer function.
- A works council or a data protection authority asks for a staff path, with a legal reason.

## Sources

- Regulation (EU) 2016/679, the [GDPR](https://eur-lex.europa.eu/eli/reg/2016/679/oj), Article 25(2). By default, personal data are not "made accessible without the individual's intervention". The share is that intervention.
- GDPR Article 15(3). The controller "shall provide a copy of the personal data undergoing processing". The delivery (#5390) gives the copy to the person and to nobody else.
