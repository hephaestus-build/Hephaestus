---
"hephaestus": patch
---

Workspaces connected to multiple integrations keep receiving events from each one when another integration's event subscription needs a retry. Stopping a workspace also accounts for every active subscription, including one that initially failed to stop.
