---
"hephaestus": minor
---

Retry a message to Ask Hephaestus with the same key and body within 72 hours. The API returns the question you already sent.

**Operators:** Add the `Idempotency-Key` header to turn requests before the upgrade. Remove `requestId` from the body after the upgrade.
