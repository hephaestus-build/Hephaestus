---
"hephaestus": patch
---

A model provider that stops answering without an error now slows its callers too. Some providers and gateways send the start of an answer, and then nothing or only an error event. The call limit now falls when a stream ends without content, or when it waits for its first content longer than the provider's usual first token allows. Such a call no longer raises the limit. The first-token time now counts only text, reasoning or a tool call, so an empty or failed stream cannot make the provider look faster than it is.
