---
"hephaestus": minor
---

Workspace owners can deliver approved access requests to their GitHub organization and selected teams using a separately installed Hephaestus Access App. This works without a directory; directory-group eligibility remains optional. Organization owners authorize each target without joining the workspace. Developers can track invitations and leave managed targets; administrators can retry or pause reconciliation without losing pending removals. Expiry ends request eligibility without waiting for a notification job, pending renewals do not extend it, and request-history cleanup waits for confirmed external removals. Existing access requires explicit adoption, and account or workspace deletion preserves unresolved external-access obligations.

Operators can optionally configure the Access App independently of the normal GitHub integration. GitLab identity linking remains supported; external GitLab, Slack and Outline membership provisioning is not supported.
