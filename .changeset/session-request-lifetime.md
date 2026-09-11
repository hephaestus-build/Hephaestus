---
"hephaestus": patch
---

Opening a page no longer fails when the shared session check is still running as the interface remounts. Signing out still discards pending identity results, and server outages remain visible rather than being treated as a signed-out session.
