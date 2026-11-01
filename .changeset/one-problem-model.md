---
"hephaestus": minor
---

The REST API now reports every error the same way. A client tells problems apart by the RFC 9457 `type`, which is an address such as `https://docs.hephaestus.build/contributor/api-error-handling#step-up-required`. The `code`, `reason`, `kind`, `reasonCode` and `error` members that named the kind of error are gone from error bodies, and the relative `/problems/…` and `urn:hephaestus:csrf` types are replaced. Members that carry data about one problem, such as `withholdingReason` and `callbackError`, stay. Workspace creation no longer accepts `ownerUserId`: the signed-in creator owns the workspace. The internal sandbox endpoints are no longer in the published API description, and the feature flags in `GET /user/features` are always present.

**Operators:** before you upgrade, update each API client or script that reads a removed member. The migration guide names the member to read instead.
