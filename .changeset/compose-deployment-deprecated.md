---
"hephaestus": patch
---

The Docker Compose deployment is deprecated. From 1.0.0, the Helm chart is the only supported production deployment, on a Kubernetes cluster or on one host with k3s. Compose stays supported for the rest of the 0.x releases, and a migration guide that moves a Compose install to the chart follows before 1.0.0. This release needs no action. To plan the move, read [Supported deployment](https://docs.hephaestus.build/admin/compatibility-policy#supported-deployment).
