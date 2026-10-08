---
"hephaestus": patch
---

A pull-based host that already applied its environment's promotion no longer restarts its stacks or stops its worker when another environment is promoted. It still retries its own promotion until it applies, and a new promotion of the release it already runs still applies again.
