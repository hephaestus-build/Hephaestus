---
"hephaestus": minor
---

Reviews can continue safely after a server restart. Hephaestus reuses verified frozen inputs when preparation finished. If preparation was interrupted, a fresh attempt starts without overwriting the earlier snapshot.

**Operators:** The chart now counts the capture lease, preparation lock and publication transaction for each review, plus boot work. Use the automatic database pool size or increase a fixed size to the minimum that the chart reports.
