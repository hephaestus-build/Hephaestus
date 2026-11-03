---
"hephaestus": minor
---

Operators can now see how practice reviews end. `practice_review_ended_total` counts each review by its status and stop reason. `practice_review_items_ended_total` counts its items by their last state, with the reason for each withheld item. A new alert, `HephaestusReviewsIncomplete`, fires when more than a quarter of the item reviews of six hours do not end complete. Load the new rule file to get it. Each runtime role also exports `application_build_info`, which names the release that each process runs. The observability guide now lists the questions that the signals answer, the questions without a signal, and the objectives for 1.0.
