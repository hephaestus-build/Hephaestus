#### 🔴 Log context uses consistent field names

Update queries, saved searches, and collector rules that read these fields:

| Previous field | New field |
| --- | --- |
| `context.installation_id` | `context.installation.id` |
| `context.proxy.apiProtocol` | `context.proxy.api_protocol` |

Records inside an outbox attempt carry `context.action.id`, `context.action.kind`, and `context.action.attempt`.
The attempt count is distinct from a run's lease epoch, `context.run.epoch`.
