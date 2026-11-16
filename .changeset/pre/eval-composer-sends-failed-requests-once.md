---
---

No user-facing change. The composer can now send a failed model request only once, which the feedback eval uses so that a provider failure counts as one call. Reviews keep retrying a failed request as before.
