---
"hephaestus": minor
---

Webhook deliveries now wait in PostgreSQL instead of NATS JetStream. The server stores each verified delivery for every workspace that monitors its repository, channel or connection before it answers the provider, so a repository that a workspace starts to monitor misses no event. A delivery that a provider sends again is stored once. Dead letters keep their payload: a replay works for 30 days after the failure. Erasing a person also deletes the deliveries that name them.

**Operators:** drain the webhook streams before you upgrade, and replay the dead letters that you want to keep: the upgrade does not carry them over. The chart refuses `webhook`, whose last setting `webhook.stream` is gone, and `config.consumerInactiveThreshold`. The stream alerts are gone; `HephaestusProviderEventsWaiting` and `HephaestusProviderEventBacklogUnsampled` watch the inbox, and `integration_consumer_retry_total` replaces `integration_consumer_nak_total`.
