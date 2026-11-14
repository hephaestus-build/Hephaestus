---
"hephaestus": minor
---

Failed reviews and conversation turns now have a failed run outcome. Timed-out and cancelled work keeps its own outcome, and evidence refusal keeps its recorded reason. A review keeps the run that measured it after its composition starts, and deletes that run with the review.

**Operators:** Stop every role before this upgrade, as **Stop every role before recording failed and handed-off run outcomes** describes. The previous server cannot read the new run outcomes.
