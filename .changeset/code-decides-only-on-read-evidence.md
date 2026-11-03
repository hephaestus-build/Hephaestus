---
"hephaestus": minor
---

A practice script can no longer report a result that it decided without its evidence. A script that catches a failed read, or reads a record that was not captured, and then returns its own result stops with the reason "Decided without its evidence". Earlier, such a script could record Not met or Could not determine about the work. The labels for an unavailable or refused read also no longer blame a model.

**Operators:** if a practice script of yours catches a read failure and returns its own result, it now stops with that reason. Let the review decide with `ctx.review`, or let the failure end the script.
