---
"hephaestus": minor
---

CSRF denials use RFC 9457 problem responses in both workspace address modes. The generated API contract declares the problem media type and keeps extension fields, such as validation errors and request references, at the top level.

**Operators:** Update custom API clients to read `application/problem+json` responses and their top-level fields. CSRF denials use the `csrf-token-rejected` problem type. The migration guide gives the client steps.
