---
"hephaestus": patch
---

Scrubbing the GitHub issue of an erased product feedback report now goes through the same outbox as filing. Each scrub retries with the outbox's backoff and still becomes a manual step for an admin when it has not finished after 30 days. A scrub whose answer from GitHub was lost first looks for an issue it may already have moved, and repeats only the redaction and the close, which are safe to repeat. A step that GitHub refuses goes to an admin at once.
