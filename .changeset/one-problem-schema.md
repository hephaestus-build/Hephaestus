---
"hephaestus": minor
---

Every error that the API returns now has one documented shape. The OpenAPI description gives each operation an `application/problem+json` error response with the problem schema. It lists every problem type, so a client that you generate can branch on the type and not on the message text. Each problem always has `status` and `title`. A request without a valid sign-in, or one that the server refuses before it reaches the endpoint, now gets the same problem body. Before, it got an empty body. The `WWW-Authenticate` header is unchanged.
