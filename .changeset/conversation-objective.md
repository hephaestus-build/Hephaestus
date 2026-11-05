---
"hephaestus": minor
---

Two new alerts watch conversations. `HephaestusConversationFirstTokenBudgetBurnFast` and `HephaestusConversationFirstTokenBudgetBurnSlow` fire when too many turns start their answer later than 10 seconds, against an objective of 95 % within 10 seconds. `HephaestusConversationTurnsFailing` fires when more than a fifth of an hour's turns fail or lose their worker. Turns that a developer stops do not count. Load the rule file of the release to get them.
