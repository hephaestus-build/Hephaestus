---
"hephaestus": minor
---

The server now refuses to start without its system encryption key and its credential encryption key, in every profile. The message names the missing setting.

A container of the server image that sets no `SPRING_PROFILES_ACTIVE` now refuses to start, instead of starting with the image build's profile.

**Operators:** A deployment through the chart already sets both keys and its profile, and needs no change. A server outside the chart must set `HEPHAESTUS_SECURITY_ENCRYPTION_KEY`, `HEPHAESTUS_SECURITY_CREDENTIAL_ENCRYPTION_KEY` and `SPRING_PROFILES_ACTIVE`. The `e2e` profile no longer supplies default keys.
