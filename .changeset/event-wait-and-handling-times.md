---
"hephaestus": minor
---

The server now reports how long provider events wait before their first handling, and how long handling takes, per provider. `HephaestusEventsDelayed` fires when more than 10 % of a provider's events keep waiting over five minutes for 15 minutes.
