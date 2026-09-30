---
"hephaestus": patch
---

A pull or merge request is no longer reviewed a second time for a problem when nothing changed since the review that found it. Before, an edit held back during a burst of activity could, once it settled after that review, start another automatic review of the same title, description and commits and repeat its feedback. A problem is now rechecked only when the title, description or latest commit differs from what its review read; practices the edit or push is an occasion for still run as before.
