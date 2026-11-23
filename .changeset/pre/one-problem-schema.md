---
"hephaestus": minor
---

Every error that the API returns now has one documented shape. The OpenAPI description gives each operation `application/problem+json` error responses with the problem schema. It lists every problem type, so a client that you generate can branch on the type and not on the message text. Each problem always has `status` and `title`. Some errors had an empty body before and now carry a problem too: a request without a valid sign-in, a request that the sign-in check refuses, an ended session refresh, and a request body that is too large. The `WWW-Authenticate` header is unchanged.
