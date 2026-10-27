---
"hephaestus": minor
---

The webhook receiver now names why it could not store a delivery. Each `503` it answers has a log record with `event.name=webhook.publish.failed` and a reason: a timeout, no responders, a dropped or closed connection, a full stream, or a rejected message. Within 8 seconds it also retries failures that the event broker answers at once, instead of retrying once. A delivery that GitHub sends again within 3 hours is stored once, which covers the receiver's own redelivery requests; existing streams take the longer window at the next start. A storage stall longer than 8 seconds still ends in `503`.

**Operators:** if you set `HEPHAESTUS_WEBHOOK_PUBLISH_TIMEOUT` or `HEPHAESTUS_WEBHOOK_PUBLISH_MAX_RETRIES`, delete them; the receiver ignores them now. Set `HEPHAESTUS_WEBHOOK_PUBLISH_DEADLINE` (default `8s`, below `10s`) if you need another limit.
