---
"hephaestus": patch
---

While silent mode is on, Hephaestus no longer rotates GitLab tokens, and the workspace shows the token as expiring instead. A restore clone that holds copies of the live tokens thus cannot revoke the tokens of the live installation. The connection audit now records each GitLab token rotation, and a failed token check logs the HTTP status of GitLab's answer.
