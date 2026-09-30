---
"hephaestus": minor
---

Operators can scrape Prometheus metrics without a user token from every runtime role on management port 9090. The supported Compose stacks keep this port on the private container network, without a host publish or proxy route. The public application port refuses the metrics endpoint. The observability guide now includes scrape configuration and alert rules for integration poison events, stale stream polls, LLM budget limits and failed agent jobs.

Liveness and readiness remain available on the application port at `/livez` and `/readyz`. Container health checks use these application-port paths. **Operators with custom deployments:** Before upgrading, restrict the management listener to a trusted private network and update any management probes to port 9090 (or your distinct `MANAGEMENT_PORT`).
