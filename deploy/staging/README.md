# Staging platform configuration

`hephaestus-staging` runs the Helm chart on a single k3s node beside the Docker host of other services.
These files are the platform and the host-specific parts of that installation. They are staging's own configuration, not chart behavior, and no other installation needs them.

| File | Applied with | Purpose |
| --- | --- | --- |
| `cert-manager.yaml` | `kubectl apply -f` | cert-manager through the k3s Helm controller, with Gateway API support |
| `traefik.yaml` | `kubectl apply -f` | k3s Traefik as the Gateway controller on host ports 80 and 443 |
| `gateway.yaml` | `kubectl apply -f` | The Let's Encrypt issuer, the Gateway `edge`, the HTTP to HTTPS redirect and the redirect of the TUM alias |
| `coolify-passthrough.yaml` | `kubectl apply -f` | TLS passthrough and plain HTTP for `logos.` and `stats.hephaestus.felixdietrich.com` to the Coolify proxy |
| `coolify-forward@.service` | `systemctl enable --now coolify-forward@8080 coolify-forward@8443` | Host forwarders from the Pod network to the Coolify proxy |
| `worker-to-traefik.yaml` | `kubectl apply -f` | Lets the worker reach the model endpoint, which this host serves |

The site values, the Secrets and the certificates of PostgreSQL stay on the host (`/etc/hephaestus/chart.yaml`, the Secrets `hephaestus-app` and `hephaestus-postgresql-tls`).

## Shared host workarounds

Two other services, `logos.` and `stats.hephaestus.felixdietrich.com`, run on this host behind the Coolify proxy.

- The Coolify proxy listens on the Docker bridge address `172.17.0.1:8080` and `:8443`, and k3s Traefik passes their traffic through.
- Docker rewrites a connection from the Pod network to that address to the proxy container, but the proxy's reply does not return to the Pod. So two `socat` forwarders on the Pod bridge address (`10.42.0.1:18080` and `:18443`) connect to the proxy from the host itself.
- When the Coolify proxy container is recreated, connect it to the network of the stats service again: `docker network connect jwc00og coolify-proxy`.
- The model endpoint of the instance is `logos.`, so a worker connection to it ends at the Traefik Pod, a private address that the chart's egress policy leaves out. `worker-to-traefik.yaml` allows only that path, only for the worker.

Remove `coolify-passthrough.yaml`, `coolify-forward@.service` and `worker-to-traefik.yaml` when logos and stats move off this host.
