---
"hephaestus": minor
---

Operators can now see whether feedback reached the work when Hephaestus was not sure that its write landed. `practice_feedback_readback_total` counts each such check by provider and by what it showed: the feedback was found, proved absent, or still unknown. A new alert, `HephaestusFeedbackReadbackUnknown`, fires when a check could not tell, because that feedback may be missing or could be posted twice. Load the new rule file to get it.
