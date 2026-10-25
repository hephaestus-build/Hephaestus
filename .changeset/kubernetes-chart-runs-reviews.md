---
"hephaestus": patch
---

The Helm chart now runs practice reviews when the worker is enabled: before, the server submitted none and the workers claimed none. `worker.hephTurns` sets how many Heph turns one worker runs at once, and the single-node profile sets 1 so that its sandbox quota holds them. The chart also passes the settings that Compose installations could set and the chart could not: the research organization, public activity, the second currency for AI spend, the passkey requirements, the repository sync schedule, and implicit TLS or an unauthenticated relay for email. A new profile, `small-16gib.yaml`, applied after `small.yaml`, fits a single node of 16 GiB: the worker restarts in place for each upgrade (`worker.rolloutStrategy: Recreate`), so reviews and Heph pause for the drain, while the webhook keeps receiving.
