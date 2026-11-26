---
"hephaestus": patch
---

A review occasion whose original work Hephaestus can no longer read no longer retries without end. It ends withheld with "The original work for this event is unavailable". Before, each such occasion left its event publication incomplete, so `HephaestusEventPublicationsOverdue` fired for `ItemEventReviewListener` and the backlog grew every hour. A removed item ends the same way, with its own reason. A pull request whose repository the workspace no longer monitors ends as not visible, never as removed work. A capture failure that can still pass stays retryable.

After the upgrade:

1. The server completes the stuck publications that still have attempts left. Each run sends up to 50 of them again, within the in-flight limit, and runs are five minutes apart. At full throughput, 4,000 publications take about seven hours. That is an estimate, not a guarantee.
2. Publications that used all ten attempts are exhausted, and the server does not retry them. Replay them with [Replay exhausted durable work](https://docs.hephaestus.build/admin/production-operations-runbook#replay-exhausted-durable-work) for `ItemEventReviewListener`.
3. Check that `event_publications_overdue` and `event_publications_exhausted` for the listener are 0. Also check that no `ItemEventRecorded` row in `event_publication` stays incomplete.

Do not delete rows from `event_publication`.
