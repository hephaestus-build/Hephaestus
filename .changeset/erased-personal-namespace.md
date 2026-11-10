---
"hephaestus": patch
---

Erasing a person now also removes their login from a purged workspace that was installed on their personal account. Its slug, login and connection names become `erased-<id>` and its name becomes `Erased workspace`. Only a keyed hash of the old slug remains, so nobody can take that slug. While such a workspace is still active, it keeps the login; purge it first if the person asks for the login to go.
