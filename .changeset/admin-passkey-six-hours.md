---
"hephaestus": minor
---

An admin's passkey verification now opens protected admin access for six hours, not for the whole seven-day session. After six hours, Hephaestus asks the admin to verify a passkey again; the session and member access continue. Sensitive actions still ask for a verification within the last five minutes.

Operators can change the window with `config.auth.passkeys.adminMaxAge` (`HEPHAESTUS_AUTH_PASSKEYS_ADMIN_MAX_AGE`).
