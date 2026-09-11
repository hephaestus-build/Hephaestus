---
"hephaestus": patch
---

Stopping a streamed model request now cancels the upstream connection and releases queued network buffers instead of retaining a replay of the abandoned response. Practice reviews and conversations keep streaming usage accounting without retaining the whole response in memory.

Provider stream failures and client disconnects now mark the model operation as failed in exported traces even when HTTP 200 headers were already sent. The trace preserves the committed HTTP status separately from the stream outcome.
