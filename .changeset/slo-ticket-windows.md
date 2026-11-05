---
"hephaestus": minor
---

Three new warnings find slow problems that the existing alerts miss. `HephaestusApiErrorBudgetBurnTicket` and `HephaestusApiLatencyBudgetBurnTicket` fire when the API spends its error or latency budget at its full rate for three days. `HephaestusPageExperiencePoorWeekly` fires when fewer than 75 % of page loads in seven days show their content within 2.5 seconds. It needs only 50 loads in a week, so it also works on a small instance.
