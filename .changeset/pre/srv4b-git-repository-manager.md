---
---

Refactor with no user-facing effect: the server's handling of repository mirrors is split, so that review checkouts, commit history reads, and mirror cleanup each have their own part.
