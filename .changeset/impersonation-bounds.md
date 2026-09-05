---
"hephaestus": patch
---

Impersonating an account now ends when it should. It cannot outlive the operator's own session, it
ends as soon as the operator stops being an instance administrator, and it ends if the account being
impersonated is promoted to instance administrator — the case an operator could previously reach only
by starting over. Each of these returns the operator to their own session and is recorded in the
audit viewer as the end of the impersonation, with the reason.

Leaving an impersonation that has already ended is now refused instead of quietly handing back a
session, and an ordinary session can no longer be renewed past its absolute lifetime.
