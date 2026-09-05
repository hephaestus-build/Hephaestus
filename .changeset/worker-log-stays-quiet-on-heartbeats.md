---
"hephaestus": patch
---

A worker's log no longer fills with warnings about the heartbeats its own hub sends. The hub answers
every capacity report with a heartbeat to keep an idle control channel alive, and the worker was
reporting each one as a protocol violation, roughly three warnings a minute per worker.
