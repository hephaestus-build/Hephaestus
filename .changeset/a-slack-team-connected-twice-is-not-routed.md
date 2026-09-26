---
"hephaestus": patch
---

Slack events for a Slack workspace that is somehow connected to more than one Hephaestus workspace are no longer delivered to either one; the instance logs an error naming the Slack team so an operator can resolve it. Connecting a Slack workspace that is already connected elsewhere now tells you it is taken without naming the Hephaestus workspace that holds it, unless you administer that workspace.
