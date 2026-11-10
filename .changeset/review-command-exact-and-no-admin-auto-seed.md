---
"hephaestus": patch
---

On GitLab, a `/hephaestus review` comment starts a review only when the comment holds nothing else, as on GitHub and as the user guide says. Previously, any text after the command also started one.

A fresh server no longer makes the first person who opens an empty workspace its admin. That shortcut is now for local development only: it is off unless `hephaestus.workspace.auto-seed-membership` is set, and the server refuses to start with it on under the `prod` profile. The Helm chart always runs `prod`, so a chart install needs no action.
