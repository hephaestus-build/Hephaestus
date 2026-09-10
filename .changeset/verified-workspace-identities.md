---
"hephaestus": patch
---

Prevent changed or reused provider usernames from granting another developer's workspace access, exposing their account preferences, or attributing Slack and Outline activity to them. Workspace membership responses and exports report the strongest role across linked identities while keeping the selected developer independent of role changes and newly linked accounts.

Slack consent changes and feedback notifications use the verified developer. Suspended accounts and accounts awaiting deletion cannot start Slack mentor turns or receive Slack feedback notifications.

Account settings preserve provider-synced profile details. When a profile must be created and its saved username now belongs to another identity on the same provider, settings report a conflict rather than changing that other developer's profile.

Workspace admins cannot demote owners. Manual role changes and membership removals preserve the last workspace owner.

GitHub installations no longer attach to an existing workspace solely because an account name matches. Reinstallation and automatic PAT promotion require the same recorded GitHub organization identity; personal-account and legacy workspaces without that identity stay separate. Existing installation bindings continue working across account renames. Workspace creation requires a connected SCM account, and administrator elevation cannot reveal another developer's private mentor conversations through a matching display name.
