---
"hephaestus": patch
---

A call pool now reads the rate limit headers of Anthropic and OpenAI and pauses briefly when a provider's bucket is empty, so an empty bucket no longer lowers the limit of calls in flight. An instance admin can set `inputTokensPerMinute` and `outputTokensPerMinute` for a provider with separate token buckets. The tokens that a provider reports now replace `tokensPerCall` when a call ends, so a conservative bound no longer holds back calls for a full minute. Anthropic's spend limit now reaches the caller at once instead of waiting.
