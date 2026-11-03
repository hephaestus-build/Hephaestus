---
"hephaestus": minor
---

Operators can now see whether feedback reaches each destination, and how long a developer waits for it. `practice_feedback_settled_total` counts feedback that is delivered, withheld, failed or rejected, per destination, with the reason for withheld feedback. `practice_review_first_delivery_seconds` measures the time from a push or a request to the review's first posted feedback. `practice_feedback_oldest_waiting_seconds` gives the age of the oldest feedback on the work that waits for its first post. Three new alerts use them: `HephaestusFeedbackDeliveriesFailing` fires when three deliveries to one destination fail in one hour, `HephaestusFeedbackDeliverySlow` when more than 30 % of the reviews of six hours post their first feedback later than 30 minutes, and `HephaestusFeedbackDeliveryStalled` when feedback on the work waits more than two hours. Load the rule file of the release to get them.
