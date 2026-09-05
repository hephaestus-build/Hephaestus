---
"hephaestus": minor
---

Docker sandbox settings now live under `hephaestus.sandbox.docker.*`. Interactive sessions use the configured Docker connection rather than an inherited Docker context.

**Operators:** Rename custom Spring property overrides and replace `SANDBOX_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` with `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_DOCKER_CONTAINER_RUNTIME`. TLS verification requires an explicit certificate directory. Removed names have no aliases: a worker-role process refuses to start while any of them is still set. Follow the migration guide before upgrading. The Docker host environment variable and gateway port are unchanged.
