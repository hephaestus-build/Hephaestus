---
"hephaestus": minor
---

Protect admin access with passkeys. Workspace owners can require passkeys for their admins and owners. Instance policy takes precedence over workspace and personal choices.

**Operators:** Production instance administration now requires passkeys by default. Before upgrade, read the passkey enrollment and recovery procedure. Configure the trusted browser origin if it differs from the instance URL. You can explicitly select optional instance-admin protection through `HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED=false`.
