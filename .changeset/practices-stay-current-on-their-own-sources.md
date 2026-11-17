---
"hephaestus": patch
---

Automatic pull request reviews no longer withhold their practices when someone pushes to another branch, adds a tag, or when a bot comments on the pull request. A practice now counts as outdated only when a source it reads changes: the pull request's commits, its record, or a source the practice declares, such as the discussion. Results that a review recorded before this release can count as outdated once, and the next update of the pull request reviews them again.
