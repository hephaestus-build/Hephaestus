---
"hephaestus": minor
---

The OpenAPI description now marks each enum that a public operation returns as open: a string with its known values as examples. A client that you generate from it, in any language, keeps working when a newer server sends a value that the client does not know. Observation outcomes and problem severities stay closed. Responses are unchanged.
