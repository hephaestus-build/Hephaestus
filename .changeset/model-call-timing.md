---
"hephaestus": patch
---

For models using OpenAI chat completions or the OpenAI Responses adapter, review trace records include adapter call times and the time to receive response headers. Failed and aborted calls retain their timing, including waits for retries within a call. Debug records also distinguish an adapter-reported failure reason from a missing or unreadable reason, and Responses failures name the stream's own failed, incomplete or error ending. Calls through other provider adapters remain untimed and are identifiable from the timed-call count.
