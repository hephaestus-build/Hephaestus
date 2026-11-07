---
"hephaestus": minor
---

Hephaestus verifies the broker's hostname when it connects to NATS over TLS.

**Operators:** For external NATS over `tls://`, confirm that each broker's certificate names its configured or advertised connection address before you upgrade.
