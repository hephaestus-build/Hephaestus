---
"hephaestus": minor
---

The production server now refuses to start unless its API issuer is a root HTTPS origin.

**Operators:** Set `hephaestus.auth.issuer` to the public API root origin, for example `https://api.example.test`. Keep an API prefix in `hephaestus.auth.api-base-path`. Changing the issuer rejects existing access tokens, so users must sign in again. Follow the migration instructions before upgrading.
