---
"hephaestus": patch
---

A server no longer makes the first person who opens a workspace with no members its admin, whatever profile it runs with. Before, only the production profile turned this off. The server now refuses to start if `hephaestus.workspace.auto-seed-membership` is on under the production profile. The Helm chart always runs that profile, so a chart install needs no action. For local development, sign in with the default workspace's admin, or set the property yourself.
