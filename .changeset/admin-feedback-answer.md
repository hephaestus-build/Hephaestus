---
"hephaestus": minor
---

Workspace admins now see a developer's answer to feedback and, for a disagreement, its explanation. A developer's rating and their other notes are no longer shown in the feedback sheet, and a rating without an answer is not shown at all. The time of an answer is when the developer gave it, also after they change their rating. For API clients: the feedback detail of a workspace admin, and the answers to its recheck and retry, replace `response` with `answer`. `answer` holds `resolution`, `createdAt`, and `comment` only for a disagreement, and it is `null` when the developer gave no answer.
