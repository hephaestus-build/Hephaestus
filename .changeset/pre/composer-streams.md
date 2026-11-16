---
"hephaestus": patch
---

Feedback no longer waits an extra 10 minutes when its composition takes long. The feedback composer's model calls now stream, so the model proxy no longer cuts a composition that is still writing after 5 minutes and retries it. The proxy now serves only streamed model calls from a sandbox, and it logs a provider that sends nothing as stalled, not as unreachable.
