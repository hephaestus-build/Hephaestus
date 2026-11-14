---
"hephaestus": minor
---

The chart can now set the historical backfill and the daily email budget, which the admin docs already described but a Helm install could not change. `config.sync.backfill.enabled`, `batchSize`, `rateLimitThreshold` and `intervalSeconds` set the backfill of issues and pull requests after the recent sync. `config.email.rateLimit.dailyCapacity` and `optionalDailyCapacity` set how many emails, and how many optional emails, the instance sends per day. The defaults are unchanged.
