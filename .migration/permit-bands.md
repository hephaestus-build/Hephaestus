#### 🔴 Change the model call queries that read `class` or `queue`

Model calls now have a band, `interactive`, `batch` or `background`, in place of a call class.
The bundled alerts and dashboards read the new label. If you have your own queries, alerts or dashboards on these metrics, change them:

- `llm_pool_permits_active`, `llm_pool_permit_wait_seconds`, `llm_pool_permit_abandoned_total`, `llm_pool_permits_granted_total`, `gen_ai_client_operation_time_to_first_chunk_seconds`, `gen_ai_client_operation_duration_seconds`, `gen_ai_client_calls_total` and `gen_ai_client_tokens_total` have the label `band` in place of `class`.
  `class="turn"` becomes `band="interactive"`.
  `composition`, `request` and `event` become `band="batch"`.
  `schedule`, `backfill` and `product_feedback` become `band="background"`.
- `llm_pool_waiters` has the label `band` in place of `queue`.
  `queue="conversation"` becomes `band="interactive"`.
  `queue="background"` becomes `band=~"batch|background"`.

Series with the old labels stop at the upgrade.
