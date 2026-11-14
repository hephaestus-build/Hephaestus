---
"hephaestus": patch
---

Slow provider calls during workspace bootstrap and GitLab token checks no longer hold database transactions open. GitLab token checks reject stale answers after a connection or token changes, and a stalled server can no longer rotate a token that another server already rotated. Concurrent GitHub user updates release their transaction before retry backoff. A scheduled job on one server can no longer release a lock that a later run on the same server holds.
