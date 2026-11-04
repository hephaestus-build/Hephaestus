---
"hephaestus": patch
---

The server now counts the database reads that a broad tenancy exemption lets through, in the `tenancy.bypassed_violation.total` metric, by the code that holds the exemption. Nothing is refused, and nothing else changes.
