---
"hephaestus": patch
---

Five early practices that repeat catalog practices are retired: `pr-scope`, `commit-message-quality`, `pr-description-quality`, `error-handling` and `review-thoroughness`. They could tell a developer opposite things in one review, and they added model calls to every review. The upgrade changes only copies whose definition nobody edited, and it needs no action.

If a copy has no review history, the upgrade deletes it. If a copy has review history, it stays with Reviewed by set to Nobody and feedback set to Off. Hephaestus stops reviewing it, its results stay, and its feedback cards leave the practice page. Its page in practice administration says why it was retired and how to bring it back: set Reviewed by to Hephaestus, remove the note and choose a feedback mode. `review-thoroughness` also needs review events that its kind of work offers, because its current event is not one of them. A copy whose definition you edited, or that the catalog owns, does not change, so it can still disagree with the catalog until you turn it off.
