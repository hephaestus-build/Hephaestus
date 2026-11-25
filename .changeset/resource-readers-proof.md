---
"hephaestus": patch
---

Hephaestus now records, for each monitored GitHub repository, whether GitHub showed it public to the workspace's own credential in its last sync. A repository stops counting as public for every workspace once GitHub reports it private, any workspace's sync sees it as not public, or it is deleted. Private, internal and GitLab repositories wait until a later release checks who can see them. Nothing changes yet in what Hephaestus reads. The new metric `monitored_repository_readers` counts monitored repositories by visibility class and proof state. You do not need to change your configuration.
