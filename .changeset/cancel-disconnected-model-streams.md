---
"hephaestus": patch
---

Stopping a streamed model request now cancels the upstream connection and releases queued network buffers instead of retaining a replay of the abandoned response. Practice reviews and conversations keep streaming usage accounting without retaining the whole response in memory.
