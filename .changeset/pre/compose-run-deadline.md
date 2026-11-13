---
"hephaestus": patch
---

Feedback composition no longer fails on its first model call after a review is sealed. Each composition receives its configured work window after any retry delay. Model calls and provider readback share the same deadline.

Provider readback preserves explicit editor facts, so feedback that no one edited remains available for checked correction.
