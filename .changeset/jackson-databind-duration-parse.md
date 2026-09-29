---
"hephaestus": patch
---

Updates the server's JSON library to fix a denial-of-service vulnerability, in which a request with an
oversized duration or date value could hold a server thread for minutes. No operator action is required.
