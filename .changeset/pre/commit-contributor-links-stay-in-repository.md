---
"hephaestus": patch
---

Commits link to a user through the actor a provider resolved for that email, an email stored on exactly one of that provider's users, or a GitHub noreply address on GitHub. Hephaestus no longer matches a commit to a user by an email's local part or a display name, or by the other commits in a merge request, and GitLab sync links contributors only within the repository it syncs. Any other commit stays unlinked. This change clears no existing link, and a later sync can replace one.
