---
"hephaestus": patch
---

The `hephaestus.workspace.auto-seed-membership` setting is removed. A server never makes the first person who opens a workspace with no members its admin, whatever profile it runs with. The Helm chart never turned it on, so a chart install needs no action. Operators who set it elsewhere can drop it: the server ignores it.
