---
"hephaestus": patch
---

Browser session cookies require Secure outside local HTTP E2E tests.
The server rejects insecure cookies with an HTTPS issuer or a `__Host-` cookie name.
