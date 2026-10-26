---
"hephaestus": patch
---

A practice review that is retried after a preparation or infrastructure failure now runs again. Before, the retry failed whenever the first attempt had withheld a practice before it ran, and the review ended as failed. A practice withheld before it ran keeps its reason across the retry.
