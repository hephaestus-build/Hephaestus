---
"hephaestus": minor
---

A review whose captured evidence is larger than its sandbox holds now closes as Partial and says “The work’s evidence was larger than a review can hold.” Before, it said that something the review needs was not ready. The reason describes the size of the evidence and makes no claim about the work.

**Operators:** Stop every role before this upgrade, as **Stop every role before recording reviews whose evidence was too large** describes. The previous server cannot read the new stop reason. `HephaestusReviewsIncomplete` leaves out `evidence_too_large`, as it leaves out `readiness_refused`. Make your own alerts on `practice_review_ended_total` do the same.
