---
"hephaestus": patch
---

The chart now creates the Service `<release>-sandbox-gateway` in front of the workers' sandbox gateways, and the isolation probe proves a sandbox's route through a Service on your network plugin. A worker that cannot prove that route stops at its start, and reports itself not ready later. If the Service name does not resolve, the probe treats the run as inconclusive. Nothing calls the Service yet.
