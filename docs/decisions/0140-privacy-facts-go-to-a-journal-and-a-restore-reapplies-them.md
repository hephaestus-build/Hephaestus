# ADR 0140: Privacy facts go to a journal with a copy in object storage, and a database restore reapplies them

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) (the chart and its backups), [ADR 0094](0094-a-provider-mirror-changes-only-on-a-definitive-answer-and-a-tombstone-is-not-an-erasure.md) (a tombstone is not an erasure), [ADR 0075](0075-a-transaction-is-one-use-case-and-talks-to-nothing.md) (one transaction for each use case)

**Relates to:**

- ADR 0149 (proposed in #4868, journal kinds of delivery experiments)
- ADR 0151 (proposed in #4862) decision 4 (outside effects after a restore)
- ADR 0141 (proposed in #4868, the research receipt)

## Context

A database restore goes back to a recovery point.
Every erasure and every revocation after that point is lost with the database.
Without a record outside the database, a restore brings back data that a person erased, and access that someone revoked.

Main handles erasures by hand.
An operator writes a recovery manifest (version 4) from log events, and the `erasure-recovery` profile reapplies it ([Erasure recovery](../admin/erasure-recovery.mdx)).
Step 9 of [Backup and restore](../admin/backup-restore.mdx) reapplies suspensions, session revocations and consent withdrawals by hand.

The conversation agent adds many new removals: Forget, deleted records, kept-out records, recalled transfers and research receipts.
A hand-written manifest cannot keep up.
The real constraint is that the record of a removal must outlive every backup that holds the removed data.

## Decision drivers

- A restore never brings back an erasure or a revocation.
- A replay of the record can only narrow access, never widen it.
- An instance without object storage keeps working, with a manual recovery that covers every kind of fact.
- A feature whose promise needs the journal runs only where the journal exists.
- No new service and no new database role.

## Considered options

1. **Keep the hand-written manifest.** The operator must find each new kind in the logs. A missed fact brings data back.
2. **A journal in the database only.** A restore loses the journal with the facts that it records.
3. **Journal every change,** settings and mutes included. The journal grows with every tap, and it records changes whose loss harms nobody.
4. **A hash chain or a signature on each fact.** Without an outside anchor, neither gives evidence of tampering. A forged fact can only remove data or end a grant.
5. **Hide newer rows after a restore with a seal.** Rows newer than the recovery point do not exist after a restore, so nothing needs a seal.
6. **A journal row, a copy in object storage, and a replay after a restore (chosen).**

## Decision

Option 6.

1. **A privacy fact is one `privacy_journal` row** in the transaction of its cause. It holds a kind and IDs, never content.
2. **The journal kinds are only removals and grant endings.** They are erasures, grant-ending revocations, account suspensions, session revocations, consent withdrawals and research receipts. The schema reference holds the one list.
   - Delivery experiments add `EXPERIMENT_WITHDRAWN` and `EXPERIMENT_KEY_DESTROYED`, as ADR 0149 (proposed in #4868) decides.
   - Settings, mutes, disagreements, corrections and additions are not journaled.
3. **A journal copy goes to object storage whenever a journal bucket is configured.**
   - One Helm value names the bucket, the prefix and the credential.
   - It defaults to the `privacy-journal/` prefix of the backup bucket when the chart's backups are on.
   - An operator who backs up the database another way configures a journal bucket. Examples are managed PostgreSQL and volume snapshots.
4. **Each fact is one small object, written before the person sees "done".**
   - The write uses `If-None-Match: *`, so a stored object is never replaced.
   - A conflicting retry reads the stored checksum. A match counts as stored. A mismatch raises an alert and keeps the acknowledgement waiting.
5. **A deny intent takes effect in the cause transaction.** This covers withdrawals, Forget, revocations and key destructions.
   - Person erasure puts the person's data on hold and queues the erasure. The worker waits for checksum confirmation before physical deletion.
   - Narrower removals can delete local data in the same transaction as the fact.
   - The requester's success reply and direct confirmation wait for the checksum. A feed frame can report the current local state, including a redaction.
   - While the copy is pending, the client gets `journal-pending`. The local restriction stays in effect.
   - Slack receives a transport acknowledgement after the local commit. The durable publication waits for the copy before it confirms the withdrawal to the person.
6. **The server gets one new credential.** It can write and read objects under the prefix only. It cannot list or delete. Bucket egress and trust of the bucket's private CA exist only when a journal bucket is configured.
7. **Journal objects outlive the oldest backup.** The admin guide recommends a separate journal bucket with Object Lock and a default retention. It warns that a default retention on the backup bucket blocks pgBackRest's expire.
8. **After a restore, the recovery reapplies the facts.**
   - The operator copies the prefix with the backup identity and mounts it.
   - Main's `erasure-recovery` profile reads the copy as manifest version 5. It starts with background work off.
   - It reapplies every journaled fact before the server serves data. Then it replaces every feed epoch, so each client reads again.
   - If an object cannot be read, startup stops.
9. **The full guarantee needs a journal bucket.**
   - Only the copy in object storage makes every fact survive a restore without the operator's records.
   - So an instance with no journal bucket cannot start a delivery experiment or release a research dataset.
   - The commands that start an experiment and that release a dataset check it. They return `journal-bucket-required`.
   - Startup stops when a delivery experiment runs and no journal bucket is configured.
10. **With no journal bucket, the recovery is manual, and nothing waits.**
    - The operator writes manifest version 5 by hand from the restricted incident record, as on main.
    - Version 5 lists every journal kind, Forget, experiment withdrawals and key destructions included.
    - Each journal row also writes one log event with its kind and IDs, never content. The operator cross-checks the manifest against these events.
    - The operator keeps these log events as long as the oldest backup.
    - The same hand-written manifest replaces a journal copy that is lost.
11. **Outside effects stay held until restore reconciles them,** as ADR 0151 (proposed in #4862) decision 4 decides.
12. **Other changes after the recovery point are lost.** Every person sees one notice in the app that names the recovery point.
13. **A research receipt journals what a later removal check needs.**
    - It holds the minimum disclosure metadata and the links from each subject to the released row keys.
    - It never holds the dataset bytes.

### What this changes in other ADRs

No ADR changes. The admin pages [Erasure recovery](../admin/erasure-recovery.mdx) and [Backup and restore](../admin/backup-restore.mdx) get these changes:

- manifest version 5 and its log events
- the journal bucket, and the features that need it
- the restore notice

## Consequences

- Good: with a journal bucket, a restore never brings back an erasure or a revocation, also on managed PostgreSQL.
- Good: with a journal bucket, the operator no longer writes the manifest by hand.
- Good: a forged or replayed object can only narrow access.
- Bad: the data goes on hold at once. A configured journal bucket can delay physical person erasure and the success reply.
- Bad: the server needs one more credential and, with a journal bucket, bucket egress.
- Bad: settings, mutes and disagreements after the recovery point are lost. Each person must repeat them.
- Bad: an instance without object storage cannot run delivery experiments or release research datasets.
- Bad: without a journal bucket, a restore is only as complete as the operator's manifest.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- JournalBeforeEffectTest fails when physical person erasure runs before its object is stored. It also fails when held data is read or used during a bucket outage.
- It also fails when a requester acknowledgement or a research byte leaves before its object is stored.
- JournalCoverageTest fails when a removal path writes no journal row or no log event.
- JournalCoverageTest also fails when a kind has no step in the recovery.
- A test fails when an experiment starts or a dataset is released with no journal bucket configured.
- `PrivacyRestoreDrill` restores a database snapshot. It fails when a later fact is not in effect again. It also fails when an invalid object does not stop startup, or a person does not see the notice once.

## Revisit trigger

- A restore drill finds a fact that the journal did not hold.
- Journal writes delay more than 1 % of erasure acknowledgements by more than 5 seconds.

## Sources

- Amazon S3, [Conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html). `If-None-Match: *` refuses to replace a stored object. This gives decision 4.
- Amazon S3, [Object Lock](https://docs.aws.amazon.com/AmazonS3/latest/userguide/object-lock.html). Retention is a bucket or object setting, not a prefix setting. This gives the separate journal bucket of decision 7.
