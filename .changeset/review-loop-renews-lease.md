---
"hephaestus": patch
---

Review Pods renew their own attempt leases through the sandbox gateway. Another worker can handle renewal during a worker replacement. The existing rollout drain stays unchanged.
