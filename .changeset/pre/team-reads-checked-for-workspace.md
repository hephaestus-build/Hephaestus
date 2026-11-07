---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace now inspects more reads of teams, team memberships and team repository permissions. The one read that reconciles workspace members from an organization's teams is named on its own. Removing deleted teams after a sync now reads only the teams of that provider.
