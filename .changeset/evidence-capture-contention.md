---
"hephaestus": patch
---

A practice review no longer fails at once when its evidence is still held by an earlier attempt or a cleanup. It retries within the existing retry limit instead. A review that still cannot start within that limit fails as before.
