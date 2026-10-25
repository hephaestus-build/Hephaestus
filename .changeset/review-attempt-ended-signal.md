---
"hephaestus": patch
---

A review whose model provider rejects its credentials now fails with that provider error instead of stopping as if its attempt had ended. Only the gateway's own refusal of an attempt that no longer runs stops the review.
