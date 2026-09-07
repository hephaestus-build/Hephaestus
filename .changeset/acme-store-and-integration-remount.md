---
"hephaestus": patch
---

Stops a sync started in one workspace from appearing to run in another. Switching workspace kept the integration overview's cards mounted, so a sync the new workspace had never asked for could still show as pending on the matching integration.

The certificate migration in the pull-based deployment guide no longer risks the certificates a host is already serving: the copy refuses when the volume already holds an ACME store, instead of overwriting it and warning about it afterwards.
