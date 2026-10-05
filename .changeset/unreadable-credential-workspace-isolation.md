---
"hephaestus": patch
---

A workspace whose stored credential the server can no longer decrypt no longer stops the other workspaces from starting, receiving events, or syncing, and no longer blocks their manual syncs. The server skips only that workspace and logs which connection needs a replacement credential. A manual sync of that workspace still reports the unreadable credential.
