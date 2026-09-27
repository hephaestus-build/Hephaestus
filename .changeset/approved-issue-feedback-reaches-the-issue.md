---
"hephaestus": patch
---

Approved feedback on a GitHub or GitLab issue is now posted on that issue. It used to stay prepared,
approved and sending without ever appearing, because it was addressed as if the issue were a pull
request. Feedback that fails before it is sent, because GitHub or GitLab was rate limited or could not
find the pull request, merge request or issue, is now retried and marked failed if it still cannot be
sent, instead of being held indefinitely.

**Operators:** feedback already held uncertain by this fault is not retried or re-sent automatically. A
missing Hephaestus comment on the reviewed work does not by itself prove it was never delivered; reconcile
it against the provider before any operator-approved recovery.
