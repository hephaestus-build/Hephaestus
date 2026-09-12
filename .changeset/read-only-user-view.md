---
"hephaestus": minor
---

Instance administrators can read a workspace user's practice pages and existing conversations with **View as user**, including users who have never signed in. Each view requires a reason and a recent sign-in, is audited, and cannot change anything for the viewed user. Accounts created before their developer's work was synced are matched to that work.

**Operators:** Read-only user views replace impersonation. Remove clients of the impersonation endpoints and the write-override header, drop the impersonation lifetime setting, and replace the impersonation rate-limit settings with the user-view settings; administrators who were inside an impersonation session sign in again.
