---
"hephaestus": minor
---

A provider's rate limit now slows every call to that model instead of failing it, with no setting. Every model has a call pool from the start. The pool learns how many calls in flight the provider serves: it rises while the provider keeps up, falls when the provider answers 429, 529 or a 503, and honours `Retry-After`. Calls wait and ask again, and a provider's refusal reaches a caller only when the wait would be too long or the account has no quota left. Conversations keep a quarter of the limit, and one wide review no longer holds more than its share while another waits.

**Operators:** remove `interactiveReserve` from calls to `/api/admin/llm/call-pools`, because the limit now sets the reserve. An existing `maxConcurrentCalls` stays as a ceiling on the learned limit. New metrics show the limit, the waiters, the grants and the refusals that callers never saw.
