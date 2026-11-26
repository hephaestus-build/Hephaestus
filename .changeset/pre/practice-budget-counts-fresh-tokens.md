---
"hephaestus": patch
---

A practice that needs many model calls on a large change no longer stops early with the reason budget. Its input-token budget (`PRACTICE_REVIEW_PRACTICE_INPUT_TOKENS`) now counts only what its calls read anew: the uncached prompt and the prompt written to the cache. A call no longer pays again for the part of the conversation that earlier calls already sent. A practice that keeps rereading large files still spends its budget. The limit on one review attempt counts the same way.
