#### 🔴 Rename Docker sandbox settings

Update custom application YAML, Spring property overrides, environment files and Compose overrides
before upgrading. Removed names are not aliases and may be ignored by Spring, leaving defaults in use.
The shipped single-host deployment still uses the local Docker socket and gateway port `8081`.

| Old Spring property | Replacement |
| --- | --- |
| `hephaestus.sandbox.docker-host` | `hephaestus.sandbox.docker.host` |
| `hephaestus.sandbox.tls-verify` | `hephaestus.sandbox.docker.tls-verify` |
| `hephaestus.sandbox.cert-path` | `hephaestus.sandbox.docker.cert-path` |
| `hephaestus.sandbox.container-runtime` | `hephaestus.sandbox.docker.container-runtime` |
| `hephaestus.sandbox.app-server-container-id` | `hephaestus.sandbox.docker.app-server-container-id` |
| `hephaestus.mentor.docker-cli` | `hephaestus.sandbox.docker.cli` |

- Rename `SANDBOX_TLS_VERIFY` to `SANDBOX_DOCKER_TLS_VERIFY` and `SANDBOX_CONTAINER_RUNTIME` to
  `SANDBOX_DOCKER_CONTAINER_RUNTIME`. Update any direct `HEPHAESTUS_*` environment overrides to match
  the new Spring property paths as well.
- `SANDBOX_DOCKER_HOST` is unchanged. New explicit environment mappings are
  `SANDBOX_DOCKER_CERT_PATH`, `SANDBOX_DOCKER_APP_SERVER_CONTAINER_ID` and `SANDBOX_DOCKER_CLI`.
- For TCP Docker access, mount client certificates read-only into each worker-capable container,
  enable TLS verification, and set `SANDBOX_DOCKER_CERT_PATH` to the mounted directory containing
  `ca.pem`, `cert.pem` and `key.pem`. Java operations and interactive commands now share these
  settings. Inherited `DOCKER_CONTEXT`, `DOCKER_HOST`, and Docker TLS variables no longer select
  the interactive daemon.

For defaults and connection requirements, see the
[Docker configuration reference](https://docs.hephaestus.build/admin/configuration-readiness#docker-configuration).

Restart `application-server` and `application-worker`, confirm their Docker health check, and run a
practice review and an interactive mentor session. If gVisor is configured, inspect the created
sandbox's runtime to confirm it is `runsc`; a successful application boot does not validate the
daemon's runtime registry.
