---
"hephaestus": patch
---

Deleting an organization or a person now also removes their organization memberships. The upgrade first moves memberships that earlier GitHub webhooks stored under GitHub's organization id to the stored organization, keeping the role from the full sync where both exist. It then removes memberships whose organization or person no longer exists. Each connection audit entry now records its workspace, taken from its connection.
