---
"hephaestus": patch
---

The `small.yaml` profile now holds up to 6 GiB of GitHub deliveries instead of 4 GiB. A stream keeps deliveries for the shorter of its age bound and what its byte bound holds, so at 750 MB a day the old bound kept about 5.5 days, not the documented seven. The new bound fits the existing broker budget and volume, and it applies at the next start. A new alert, `HephaestusStreamConsumerStopped`, reports a stream that keeps undelivered events for 6 hours, which the backlog alert misses on a quiet stream. `HephaestusWebhookStreamNearByteBound` is gone: a stream at its byte bound is normal, and it fired during normal operation. `HephaestusWebhookRetentionBelowAgeBound` replaces it, and fires only when a stream keeps less than its age bound.
