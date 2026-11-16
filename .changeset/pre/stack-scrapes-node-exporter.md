---
"hephaestus": minor
---

The bundled monitoring stack can now warn you when a node runs out of disk or memory. Before, it never evaluated `HephaestusNodeDiskFilling` or `HephaestusNodeMemoryLow`, even when your nodes ran node-exporter. Set `observability.stack.nodeExporters` to the IPv4 address and port of each node's node-exporter, such as `10.0.0.5:9100`. The stack then scrapes them, allows them in its network policy, and loads the node alerts. The new alert `HephaestusNodeMetricsUnavailable` fires when an address cannot be scraped, so a blocked port no longer silences the node alerts.

To use it, make node-exporter listen on an address that the Pods reach, and allow that port from the Pod network and the cluster's node addresses in the host firewall. If you evaluate the rule file with your own Prometheus, the node rules are now in the group `hephaestus-node`. Load that group if you select groups by name, and give your node-exporter targets `job="node"` so that `HephaestusNodeMetricsUnavailable` sees them.
