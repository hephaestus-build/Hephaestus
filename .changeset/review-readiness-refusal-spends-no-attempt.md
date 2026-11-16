---
"hephaestus": patch
---

A pull request review that withholds all its practices because they are not ready no longer shows a failed job or uses up an attempt, also after a server restart. The job completes without starting a sandbox. The review ends as Partial, and each practice shows its own reason: the work changed, or something the review needs was not ready. A practice whose required evidence could not be captured now gets the same reason as other practices that were not ready, so the next review of the work measures it again.

A review whose unmeasured practices were all withheld on purpose, for example one for its role and one because it was not ready, now ends as not ready. When the review settings changed while every such practice waited, the review says so. It no longer says that the review did not record an observation for every selected practice. That message now appears only when a run or the check of its results left a practice without an observation. The alert for incomplete reviews does not count reviews whose settings changed while they waited.

When a server restart interrupted a review after it had verified some practices, the next attempt lost those results: the review failed, and their feedback was never written. The next attempt now keeps the verified results and composes their feedback.
