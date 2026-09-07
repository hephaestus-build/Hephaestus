---
"hephaestus": patch
---

Account exports now record a failed attempt after a database transaction rolls back, instead of remaining queued or processing when generation fails. Successful exports are counted only after their data commits.
