---
"hephaestus": patch
---

Heph and the activity pages no longer report GitLab approvals or requests for changes that nobody gave. A merge request in a project that requires no approvals is not called approved until someone approves it. A standing request for changes is shown even when the approval rules are met. A comment on a merge request no longer turns the commenter's approval into a request for changes. Approvals that still leave required approvals missing are now recorded as the reviewer's own approvals. On upgrade, approvals that were wrongly turned into requests for changes are withdrawn. Merge requests that were marked approved with no approval behind them show no decision until the next sync reads them again.
