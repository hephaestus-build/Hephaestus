---
"hephaestus": patch
---

The webhook receiver no longer stops when several large deliveries arrive at once. Before, a few deliveries of about 25 MB at the same moment ended its process, and every delivery in flight was lost until the provider sent it again. The receiver now bounds the bytes that it holds at once and answers `503` for a delivery that does not fit, while small deliveries still pass. A new alert, `HephaestusWebhookReceiverBusy`, reports this. A delivery within a few hundred bytes of the 25 MiB limit also no longer fails at the broker: `nats.maxPayloadBytes` now defaults to 26279936. If you set this value yourself or use your own broker, raise it to at least that. The chart restarts the bundled broker for this change during the upgrade.
