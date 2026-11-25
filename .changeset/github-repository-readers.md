---
"hephaestus": patch
---

Hephaestus now records who can read each monitored private GitHub repository. The daily sync lists the repository's collaborators with the workspace's own credential, and only a complete listing counts. A listing that stops at a rate limit or an incomplete answer counts for nothing, and the repository waits until a later sync lists it in full. When GitHub removes a person as a collaborator, from a team or from the organization, Hephaestus removes that person at once. An internal repository is never proven, because no listing names the enterprise members who can read it. Nothing changes yet in what Hephaestus reads. The new metric `monitored_repository_readers_waiting_seconds` and the alert `HephaestusRepositoryReadersWaiting` show a repository that has waited more than 26 hours for its proof. You do not need to change your configuration.
