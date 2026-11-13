---
"hephaestus": minor
---

The one-host installation requests a ten-year lifetime for the internal PostgreSQL server certificate, matching its CA.

**Operators:** After applying the updated platform certificate manifest, wait for cert-manager to issue the replacement certificate. Restart PostgreSQL to load it, as the TLS certificate procedure describes.
