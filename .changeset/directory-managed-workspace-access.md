---
"hephaestus": minor
---

Workspace owners can keep access aligned with approved Keycloak directory groups. Setup separates instance approval, read-only directory credentials, a complete preview and owner activation. Administrators can inspect and reconcile the approved policy, while developers can join eligible workspaces from User settings using their verified organizational identity.

Confirmed departures remove directory-managed access. Incomplete or stale reads cannot grant access or turn an outage into a mass departure. Owners, manual exceptions and suspensions remain explicit decisions. Pausing stops new grants while confirmed removals continue; ending management removes managed access without reclassifying it as manual access. Disconnecting the relevant organizational identity also removes its managed access.

**Operators:** Directory management is optional. Approve the exact organizational issuer and permitted group IDs, and provide a separate least-privilege, read-only Keycloak client. No directory writes or external GitHub invitations are performed by this feature. Apply the additive migration to every runtime role before activation, and follow the directory setup and recovery runbook.
