---
"hephaestus": patch
---

A GitLab group webhook that a 1.0 release candidate registered now records which Hephaestus connection owns it, the first time the server checks it after the upgrade. Disconnecting such a workspace then removes its webhook from GitLab, as it does for a webhook registered by 1.0. Nothing to do for operators.
