---
"hephaestus": patch
---

A failed Hephaestus reply now records why it failed, in the `failure_cause`, `failed_call` and `failed_call_status` columns of its `mentor_turn` row: the Hephaestus host's reason, or the kind and status of its last failed model call. The cause stays after the worker restarts. Each failed model call of a conversation turn is logged as one warning with its turn. [Observability](https://docs.hephaestus.build/admin/observability) describes the columns and the log lines.
