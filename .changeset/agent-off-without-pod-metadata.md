---
"hephaestus": patch
---

A server with agent jobs off can start without Kubernetes Pod metadata under the E2E profile. The profile now honors `AGENT_ENABLED=false`, including during the seeded release upgrade.
