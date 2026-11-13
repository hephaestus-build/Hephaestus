---
"hephaestus": minor
---

Making someone a workspace owner, or taking the owner role from another owner, now asks for a recent sign-in, as instance admin role changes already do. If your sign-in is older than the configured window, Hephaestus asks you to sign in again and leaves the role unchanged. Taking the owner role from another owner now also asks you to confirm first. Changes between member and admin, and lowering your own role, stay one step. The audit log records a refused change as **Workspace owner change**.
