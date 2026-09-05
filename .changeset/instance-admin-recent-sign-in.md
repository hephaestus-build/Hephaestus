---
"hephaestus": minor
---

Sensitive instance-admin actions now ask an administrator to confirm access when their last sign-in
is more than five minutes old: changing an account's role, forcing an account out of every session,
starting an impersonation, changing a login provider, and registering or removing an LLM connection.
Linking a new identity to an account asks the same of every user, because a new link is a permanent
second way in. A stolen admin session is therefore only useful for a few minutes, and every refused
attempt appears on the audit trail.

Confirming access uses a provider the account is already linked to, and the action is never replayed
afterwards — review it and submit it again. Set `HEPHAESTUS_AUTH_STEP_UP_MAX_AGE` to change the
window. This is a local confirmation, not multi-factor authentication: an identity provider that
still holds a session may complete it without asking for anything, so enforce MFA at the provider.
