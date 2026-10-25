---
"hephaestus": patch
---

The server's background tasks no longer wait behind a long one. Before, a long task such as an Outline catch-up delayed the others, including the heartbeat that keeps a running sync alive.
