---
"hephaestus": patch
---

Person erasure no longer fails for three groups of people.
Before, the request ended as failed and kept the person's data.
- A person with a result that did not meet a practice. Erasure now removes these results with their problems.
- An administrator who removed a mark on another person's result. The mark stays, with a fixed removal reason.
- An administrator who decided on another person's feedback. The decision stays, with a fixed reason and no note.

Person erasure now also clears the login of the commit signature on the commits that the person authored or committed.
Person exports now include that login.

The upgrade replaces the database function that keeps feedback decisions append-only, so that it admits this redaction.
It needs no operator action.
