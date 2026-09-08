---
"hephaestus": minor
---

Workspace owners and administrators can grant access to an account without requiring GitHub or GitLab. Members can share their account ID from User settings. Account membership has its own role and source, and suspension immediately removes member access and prevents synchronization from restoring it. Public pages and instance-administrator access remain unaffected. Only owners can grant or remove ownership, and the final owner must transfer ownership before leaving or deleting their account.

**Operators:** Before upgrading, ensure every workspace with an owner has at least one owner who has signed in with their existing source-control identity and has an active account. The upgrade stops if that identity cannot be verified. Back up the database and upgrade all runtime roles together; older versions must not run against the new authorization model. Custom API clients must use account IDs for membership management and the separate contributors endpoint for source-control rosters. See the account membership upgrade guide.
