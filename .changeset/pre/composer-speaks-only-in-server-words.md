---
"hephaestus": patch
---

Feedback that a review composes comes only from the review's composer and its model. The server relays a composer's model request only when the request continues that composition's conversation in the order that the composer uses, with no text that the server or the model did not write. It runs a composer tool call only when that model wrote it, and only once. It refuses any other request with the reason `NOT_COMPOSER_REQUEST`, which the model proxy's refusal metric counts.
