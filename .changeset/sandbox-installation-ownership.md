---
"hephaestus": minor
---

Sandbox cleanup and capacity accounting are now scoped to an installation. **Operators:** Set a distinct `SANDBOX_DOCKER_OWNER` for installations sharing a Docker daemon, and use the same value across roles sharing a database. Drain active reviews and conversations before upgrading; legacy sandbox resources are not automatically adopted.
