---
"hephaestus": minor
---

The chart no longer sets the broker consumer prefix or how long inactive consumers stay. The server uses the `hephaestus` prefix and keeps inactive consumers for 30 days.

**Operators:** Remove `config.durableConsumerName` and `config.consumerInactiveThreshold` from chart values before upgrading. If you used a different consumer prefix, stop publishers and drain its consumers before changing it. Follow the migration instructions; the chart refuses the retired keys.
