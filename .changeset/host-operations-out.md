---
"hephaestus": minor
---

The multi-file Compose stack now takes the Let's Encrypt contact from `ACME_EMAIL`, as the single-host stack does, instead of a fixed address. **Operators:** if you run the multi-file stack, set `ACME_EMAIL` in the proxy stack's environment before you upgrade.
