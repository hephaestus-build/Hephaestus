---
"hephaestus": minor
---

The worker now watches the Pods, Jobs and ResourceQuotas of the sandbox namespace and answers its review claims and readiness checks from that view. It no longer sends the Kubernetes API a request on each claim poll or readiness probe, so a slow API server no longer slows the claim loop. The worker reports not ready while it cannot watch the namespace, and becomes ready by itself once it can.

**Operators:** the chart's Role adds `watch` on `resourcequotas` in the sandbox namespace. If you replaced the chart's Role with your own, add that verb before you upgrade.
