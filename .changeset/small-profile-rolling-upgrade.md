---
"hephaestus": patch
---

Upgrades on the `small` profile no longer stop Heph. The new worker starts beside the old one and opens its Heph host's command channel before the old worker drains. The old worker finishes its running review before it stops. A Heph turn's sandbox now requests less CPU, so both workers fit on the profile's node during the upgrade. `small-16gib` still stops the worker first, because two workers do not fit in 16 GiB.

If Heph's host cannot start within three minutes after a preparation failure, the next waiting question ends with a message that asks you to try again. A transient preparation failure leaves the question queued; the worker retries before spending an attempt.
