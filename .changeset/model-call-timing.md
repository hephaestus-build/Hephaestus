---
"hephaestus": patch
---

For models using OpenAI chat completions, review trace records include adapter call times and the time to receive response headers. Failed and aborted calls retain their timing, including waits for retries within a call. Debug records also distinguish an adapter-reported failure reason from a missing or unreadable reason. Calls without timing remain identifiable from the timed-call count.
