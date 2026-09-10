---
"hephaestus": patch
---

Prevent changed or reused provider usernames from granting another developer's workspace access, exposing their account preferences, or attributing Slack and Outline activity to them. Workspace membership responses and exports report the strongest role across linked identities while keeping the selected developer independent of role changes and newly linked accounts.

Slack consent changes and feedback notifications use the verified developer. Suspended accounts and accounts awaiting deletion cannot start Slack mentor turns or receive Slack feedback notifications.

Account settings preserve provider-synced profile details. When a profile must be created and its saved username now belongs to another identity on the same provider, settings report a conflict rather than changing that other developer's profile.

Workspace admins cannot demote owners. Manual role changes and membership removals preserve the last workspace owner.
