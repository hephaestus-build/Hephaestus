---
"hephaestus": minor
---

One-host installs verify effective kubelet reservations and allocatable memory before upgrading the application. Soft memory eviction, log rotation, image garbage collection, and bounded host journals now have explicit policies. The optional `--verify-kubelet` flag repeats the read-only cluster checks.

**Operators:** Disable host swap and its boot configuration before running the installer. An effective policy or allocatable mismatch stops the upgrade until the kubelet configuration is corrected.
