---
"hephaestus": minor
---

A new alert, `HephaestusAgentAttemptsLost`, warns you when more than 1 % of the attempts of one run kind lose their holder in six hours, for example reviews or conversation turns whose sandbox or server stopped mid-attempt. A lost attempt repeats its work, or fails the run when it has no attempt left, so a rising share costs model spend and time. No operator action is needed: a Prometheus that already loads the chart's rule file picks it up, and the runbook has a row for it.
