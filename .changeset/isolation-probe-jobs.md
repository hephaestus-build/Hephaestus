---
"hephaestus": patch
---

On Kubernetes, the sandbox isolation probe no longer leaves Pods behind when a worker stops during a probe, and a probe Pod that never gets a node no longer holds sandbox quota. Each run now takes 768 MiB of the sandbox quota instead of 1.5 GiB. Probe Pods that an earlier version left behind stay until you delete them once: `kubectl -n <sandbox namespace> delete pod -l hephaestus.build/isolation-probe=true --field-selector=status.phase!=Running`.
