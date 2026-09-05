---
"hephaestus": minor
---

Docker sandbox settings now live under `hephaestus.sandbox.docker.*`. Interactive sessions use the configured Docker connection rather than an inherited Docker context.

**Operators:** Rename custom Spring property overrides and replace `SANDBOX_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` with `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_DOCKER_CONTAINER_RUNTIME`. TLS verification requires an explicit certificate directory. Removed names have no aliases; follow the migration guide before upgrading. The Docker host environment variable and gateway port are unchanged.

Partial resource-limit overrides preserve the default process limit. Interactive startup timeouts are counted separately from runner failures.
