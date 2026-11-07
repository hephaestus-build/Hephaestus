---
"hephaestus": patch
---

Active workspaces resume webhook consumption after a local consumer stops or the broker becomes available. A workspace suspension keeps its consumer stopped through transaction completion, and a rolled-back suspension resumes consumption. Fresh consumers start with new deliveries; they do not recover messages from before their creation.
