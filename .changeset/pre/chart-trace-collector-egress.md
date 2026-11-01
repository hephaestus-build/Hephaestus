---
"hephaestus": patch
---

The Helm chart can now send traces to an OpenTelemetry collector inside the cluster. Before, its network policy blocked that path, so `config.tracing.otlpEnabled: true` exported nothing to an in-cluster collector. Set `networkPolicy.traceCollector` to the collector's namespace and pod labels. The server, worker and webhook then reach it on the port of `config.tracing.otlpEndpoint`.

Run the collector in the cluster. Outside the cluster, only the server and the worker reach a collector, and only on port 443. See [Exporting OpenTelemetry traces](https://docs.hephaestus.build/admin/observability#exporting-opentelemetry-traces).
