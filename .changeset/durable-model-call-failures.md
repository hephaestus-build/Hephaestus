---
"hephaestus": patch
---

The usage ledger retains each failed model call across server replicas.
The usage ledger records only the failure kind and HTTP status.
Late usage reports and price changes keep the first recorded failure.
