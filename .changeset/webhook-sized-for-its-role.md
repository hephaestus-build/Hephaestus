---
"hephaestus": patch
---

The webhook role now requests 640 MiB instead of 1280 MiB. Its heap is sized from what this role loads, which frees 640 MiB on the node for each webhook Pod. If you set `webhook.resources` yourself, that value stays in force, and the same heap sizing gives it more room.
