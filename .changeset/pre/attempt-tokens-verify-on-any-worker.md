---
"hephaestus": patch
---

Any worker can now verify a sandbox's credential, which prepares running attempts to outlive the worker that started them. Each worker keeps its signing key in memory and stores only the public key, in a small new table. The upgrade adds that table automatically, and nothing else changes yet.
