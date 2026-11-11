---
"hephaestus": minor
---

Outbox attempts carry their action ID, kind, attempt count, and workspace where applicable on every log record, including records from provider adapters.

**Operators:** Update searches for `context.installation_id` to `context.installation.id` and `context.proxy.apiProtocol` to `context.proxy.api_protocol`.
