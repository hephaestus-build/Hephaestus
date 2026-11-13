---
"hephaestus": minor
---

Sandbox quotas now count each template's resource requests and RuntimeClass overhead. The conversations quota also holds the two sandboxes used by the isolation gate. The one-host gVisor RuntimeClass declares 48 MiB and 10m of overhead. Set `sandbox.templates.podOverhead` to match a custom RuntimeClass. The single-node profile reserves memory for five review sandboxes.
