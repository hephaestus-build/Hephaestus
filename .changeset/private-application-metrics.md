---
"hephaestus": minor
---

Operators can scrape Prometheus metrics without a user token from every runtime role on management port 9090. The supported Compose stacks keep this port on the private container network, without a host publish or proxy route. The public application port refuses the metrics endpoint. The observability guide now includes scrape configuration and alert rules for integration poison events, stale stream polls, LLM budget limits and failed agent jobs.

Liveness and readiness remain available on the application port at `/livez` and `/readyz`. The supported Compose stacks update their health checks automatically.

**Operators:** If you use a custom deployment, before upgrading, restrict port 9090 to trusted private services. The default management bind address outside Compose is `0.0.0.0`; set `MANAGEMENT_SERVER_ADDRESS` to a private interface or enforce private ingress with firewall policy. Update custom management probes to port 9090 (or a `MANAGEMENT_PORT` distinct from both the application and sandbox gateway ports). Update application-port health checks from `/actuator/health/liveness` and `/actuator/health/readiness` to `/livez` and `/readyz`. Do not publish or proxy the management port to the internet.
