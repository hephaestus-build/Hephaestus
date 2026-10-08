---
"hephaestus": patch
---

Reviews no longer reuse a diff base from an earlier revision when a provider changes the head without supplying its base. Providers that supply the review’s diff range must provide a paired base before code is captured.
