---
"hephaestus": patch
---

A reload during a session refresh no longer signs you out.

After a rollback, stale browser tokens can stay live for up to the configured access-token lifetime (24 hours by default).
