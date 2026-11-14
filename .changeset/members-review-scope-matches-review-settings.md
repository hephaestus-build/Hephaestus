---
"hephaestus": minor
---

Members now shows the same review scope as Review settings. With **All eligible members** selected, every eligible member reads **Included**; before, every member read **Excluded**. The member search field also has a label for screen readers.

**API clients:** a workspace membership no longer has `reviewSelected`. Read `inReviewScope` instead: it says whether the workspace review scope admits the member's work.
