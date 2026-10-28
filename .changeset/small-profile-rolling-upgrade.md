---
"hephaestus": patch
---

Upgrades on the `small` profile no longer stop Heph. The new worker starts beside the old one, and the old worker finishes its running review before it stops. A Heph turn's sandbox now requests less CPU, so both workers fit on the profile's node during the upgrade. `small-16gib` still stops the worker first, because two workers do not fit in 16 GiB.
