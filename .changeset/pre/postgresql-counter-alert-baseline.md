---
"hephaestus": patch
---

The bundled monitoring stack no longer alerts on the lifetime deadlock total first seen after the metrics exporter starts.
New counter increases still trigger the alert.
