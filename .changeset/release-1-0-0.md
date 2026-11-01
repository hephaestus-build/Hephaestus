---
"hephaestus": major
---

Hephaestus 1.0 starts the 1.x upgrade guarantee. Self-hosting uses the Helm chart on Kubernetes or k3s. Docker Compose is for local development only. Reviews interrupted by the cutover resume with their stored results. An answer in progress ends with “Ask again”.

**Operators:** Install 0.95.x on the Helm chart first, then follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0). Stop the application writers and their sandboxes before migration. Export survey results before the migration removes them. This cutover cannot run old application roles on the new schema. After 1.0, an upgrade that skips a release also stops every role.
