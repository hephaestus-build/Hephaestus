---
"hephaestus": patch
---

A practice review's model call now ends when the provider sends nothing for `hephaestus.mentor.turn.model-idle-timeout` (2 minutes by default), and the review tries the call again. Before, a provider that sent its response headers and then nothing held the call, and its place in the model's call limit, until the review's work time ended. The first-byte bound, `hephaestus.mentor.turn.model-first-byte-timeout`, now applies to a review's streamed calls too.
