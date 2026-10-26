#### 🔴 The multi-file Compose proxy needs ACME_EMAIL

The proxy stack of the multi-file Compose stack (`docker/compose.proxy.yaml`) no longer sends a fixed contact address to Let's Encrypt. It reads `ACME_EMAIL`, and it does not start without it.

Before you upgrade, set `ACME_EMAIL` to the address that gets certificate-expiry notices in the environment file of the proxy stack. The single-host stack and the Helm chart are not affected.
