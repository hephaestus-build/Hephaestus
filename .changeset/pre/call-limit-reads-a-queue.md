---
"hephaestus": patch
---

A model server on your own GPUs, such as vLLM, no longer gets overloaded. Such a server queues calls and refuses none, so a call limit that learned only from refusals kept rising while answers started later. The limit now also falls when even the fastest calls wait for their first token, and it settles where total throughput peaks. No setting is needed. A new pool starts at 4 calls in flight. New metrics show the first-token time and its baseline.
