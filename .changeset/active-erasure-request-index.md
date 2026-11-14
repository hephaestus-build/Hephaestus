---
"hephaestus": patch
---

Checks for active erasure requests read an index instead of every past request, so they stay fast as erasure receipts accumulate. The upgrade adds the index automatically.
