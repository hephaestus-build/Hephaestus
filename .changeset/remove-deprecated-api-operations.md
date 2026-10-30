---
"hephaestus": minor
---

The 21 API operations that 0.95.0 deprecated are removed. The web app and the browser extension already use the operations that remain. Stored teams, memberships, reviews, evidence, usage and provider records do not change. The immediate scan and the removal of a workspace member stay available. Old workspace addresses still redirect to the current one.

**Operators:** before you upgrade, update each API client or script that calls a removed operation. The migration guide names the operation that replaces each one.
