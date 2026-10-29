---
"hephaestus": minor
---

One-host installs verify effective kubelet reservations and allocatable memory before upgrading the application. Hard eviction thresholds, log rotation, image garbage collection, and bounded host journals now have explicit policies. Soft eviction stays disabled until stateful-service request sizing is qualified. The optional `--verify-kubelet` flag repeats the read-only cluster checks.

**Operators:** Disable host swap and its boot configuration before running the installer. An effective policy or allocatable mismatch stops the upgrade until the kubelet configuration is corrected. Hosts outside Ubuntu 24.04 with the 6.8 GA kernel require the explicit `--allow-unqualified-host` qualification override. This override retains resource-policy checks.
