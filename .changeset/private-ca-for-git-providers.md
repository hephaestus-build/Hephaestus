---
"hephaestus": minor
---

A self-managed GitLab with a certificate from a private CA can now connect. Name a Secret with the CA in `config.privateCa.existingSecret`. The server and the worker trust it for sign-in, API calls and Git fetches, and the public CAs stay trusted.
