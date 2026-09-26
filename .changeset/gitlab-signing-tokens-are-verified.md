---
"hephaestus": patch
---

GitLab webhooks you have given a signing token (GitLab 19.0 and later) are now verified by their
signature. Before, the signature was not recognised and such a hook was accepted on its secret token
alone. A delivery whose signature is blank, malformed or wrong is now rejected even when its secret
token is correct, so `WEBHOOK_SECRET` must be that hook's `whsec_…` signing token. Hooks Hephaestus
creates still use only a secret token and keep working unchanged.
