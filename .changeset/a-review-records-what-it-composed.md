---
"hephaestus": patch
---

Practice reviews handle feedback submissions one item at a time, so one invalid item does not
discard valid items in the same call. The review reserves context for composition and reports
refused submissions with their reasons. Bounded recovery handles empty submissions and repeated
commands without extending the review deadline. Delivery remains subject to approval and channel rules.
