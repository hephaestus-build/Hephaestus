---
"hephaestus": patch
---

Upgrades on the `small` profile no longer stop Heph. The new worker starts beside the old one and opens its Heph host's command channel before the old worker drains. The old worker finishes its running review before it stops. A Heph turn's sandbox now requests less CPU, so both workers fit on the profile's node during the upgrade. `small-16gib` still stops the worker first, because two workers do not fit in 16 GiB.

A question that waits in the queue for three minutes can end with a message that asks you to try again. This limit follows the question across worker restarts. A follow-up stays queued while its conversation has a running answer. An unready worker leaves questions for a ready worker, without spending an attempt.

The update gives existing queued questions a fresh wait to start.
