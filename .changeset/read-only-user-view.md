---
"hephaestus": minor
---

Signing in or linking an identity now associates the account with its existing synced user; existing matching links are backfilled during the upgrade.

**Operators:** Read-only user views replace impersonation. Remove clients of the impersonation endpoints and the write-override header, drop the impersonation lifetime setting, and replace the impersonation rate-limit settings with `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_CAPACITY` and `HEPHAESTUS_AUTH_RATE_LIMIT_USER_VIEW_PERIOD`. Administrators who were inside an impersonation session sign in again.
