---
"hephaestus": patch
---

The webhook receiver keeps a delivery when the event broker answers slowly. It now waits up to 8 seconds for the broker to store a GitHub or GitLab delivery and tries again within that time, so a short storage stall no longer loses deliveries. When it still answers `503`, its log names the cause: a timeout, no responders, a dropped connection, or a full stream. A delivery that GitHub sends again within 3 hours is stored once, which covers the receiver's own redelivery requests. The settings `hephaestus.webhook.publish.timeout` and `hephaestus.webhook.publish.max-retries` are replaced by `hephaestus.webhook.publish.deadline`.
