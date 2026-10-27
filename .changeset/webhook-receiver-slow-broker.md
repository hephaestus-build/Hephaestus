---
"hephaestus": patch
---

The webhook receiver's health check no longer waits on NATS: it reads only the state of the NATS connection, so it stays fast while the broker's disk is slow. Every publish now ends before GitHub and GitLab stop waiting, and the receiver still answers `503` instead of accepting a delivery it has not stored.

Failed GitHub deliveries are now redelivered for up to 80 minutes after the first failure, with growing gaps between attempts, instead of three times in the next 30 minutes. A redelivery run sends at most 5 requests a second, stops after 5 refusals in a row or after 5 minutes, and leaves the rest for the next run, so two runs never redeliver the same events. When the receiver fails redeliveries in a row, a run only probes it with 3 redeliveries, so the other events keep their attempts until it takes deliveries again. Keep the NATS store on storage that a database or container runtime does not saturate: the broker writes every delivery to disk before it acknowledges it.
