---
"hephaestus": patch
---

Silent Mode now also stops a GitLab feedback write that was already on its way when Silent Mode was turned on. Before, such a write could still reach GitLab. Nothing changes while Silent Mode is off.
