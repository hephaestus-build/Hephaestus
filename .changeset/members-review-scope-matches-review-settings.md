---
"hephaestus": minor
---

Members now shows the same review scope as Review settings. With **All eligible members** selected, every eligible member reads **Included**; before, every member read **Excluded**. A member who is not eligible for practice review, such as a hidden member, reads **Excluded**. The Review settings preview also stops counting a selected person who is no longer eligible. The member search field now has a label for screen readers.

**API clients:** a workspace membership no longer has `reviewSelected`. The new `inReviewScope` tells whether the people part of the review scope includes the member: the member is eligible, and the people scope is all eligible members or selects them. It is not the saved selection. To read the saved selection, read `reviewScope.accountIds` from `GET /workspaces/{workspaceSlug}/practices/review-settings`.
