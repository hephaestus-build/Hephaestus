---
"hephaestus": patch
---

When a review quotes a line that is not in the change it is reviewing, the run now records which part
was wrong: the line number, the side of the diff, or the text itself. The quote is still checked
against the change exactly as before; only the explanation is new, and it is what lets a review
correct itself instead of dropping the practice.
