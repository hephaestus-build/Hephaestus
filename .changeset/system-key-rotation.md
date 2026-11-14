---
"hephaestus": minor
---

Operators can rotate the system encryption key and the JWT signing key while Hephaestus keeps serving. The system key now has an optional prior key, and the server seals each stored secret again with the new key once rotation is turned on. Setting `config.auth.signingKeyNotBefore` makes a new signing key, and earlier sessions keep working until their tokens expire. Login provider secrets and AI provider keys are now bound to their own record, like integration credentials. A server started with a system key that does not open the stored keys stops at start and names the key it needs, instead of starting and refusing every sign-in. The new chart values are `config.systemKey.rotationEnabled` and `config.auth.signingKeyNotBefore`, with the optional Secret key `HEPHAESTUS_SECURITY_PRIOR_ENCRYPTION_KEY`.

**Operators:** Stop every role for this upgrade, and keep `HEPHAESTUS_SECURITY_ENCRYPTION_KEY` unchanged. The migration Job now reads the system key to seal the stored secrets again.
