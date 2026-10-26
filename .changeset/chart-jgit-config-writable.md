---
"hephaestus": patch
---

On the Helm chart, a practice review that is retried after an upgrade or a lost worker prepares its repository again instead of failing. The server and the worker now keep Git's configuration on a writable volume, because their root filesystem is read-only.
