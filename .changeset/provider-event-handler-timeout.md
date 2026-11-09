---
"hephaestus": patch
---

A provider event whose handling hangs no longer holds back the later events of its workspace. After 30 minutes, the event returns to its stream and is retried like a failed event. After the retries, it is stored for replay. A handler that does not stop when it is told to restarts its server, so it never runs beside the next event. The server also reports a NATS broker older than 2.11 at start, in its log and as the failing `natsBroker` health component. The `HephaestusConsumerHandlerTimedOut` alert reports each timeout, and `HEPHAESTUS_INTEGRATION_CONSUMER_HANDLER_TIMEOUT` changes the limit.
