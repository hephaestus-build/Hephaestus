---
"hephaestus": patch
---

On Kubernetes, a practice review now waits in the queue while the sandbox quota has no room for its sandbox, for example while Hephaestus's sandboxes hold it, instead of being started into a refusal that used up its retries.
