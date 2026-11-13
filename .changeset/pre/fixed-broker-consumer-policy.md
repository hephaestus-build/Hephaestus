---
"hephaestus": minor
---

The chart no longer accepts a broker consumer prefix or an inactivity timeout. Provider events and replay use the PostgreSQL inbox and its scope leases.

**Operators:** Remove `config.durableConsumerName` and `config.consumerInactiveThreshold` from every values file before upgrading. Complete the provider inbox cutover in the migration instructions. The chart refuses the retired keys.
