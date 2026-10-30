---
"hephaestus": patch
---

A reload during a session refresh no longer signs you out, and neither does a renewal that arrives after another tab or workspace address already renewed the session. A stalled renewal in another tab no longer blocks your renewal. Signing out also ends the session when another tab has just used its refreshed cookie.

After a rollback, stale browser tokens can stay live for up to the configured access-token lifetime (24 hours by default).
