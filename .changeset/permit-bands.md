---
"hephaestus": minor
---

Model calls now wait for a model in bands, with a fair share for each workspace. A conversation's calls go first. A requested or event review's calls and every composition come next, and scheduled and backfill reviews' calls come last. Within a band, the workspace with the fewest calls in flight goes first, and then the review with the fewest. Calls in flight of every band count toward a workspace's share. A scheduled or backfill review's call that waits 30 seconds moves up one band, so it no longer waits behind a steady stream of other reviews' calls.

**Operators:** the model call metrics `llm_pool_permits_active`, `llm_pool_permit_wait_seconds`, `llm_pool_permit_abandoned_total`, `llm_pool_permits_granted_total`, `gen_ai_client_operation_time_to_first_chunk_seconds`, `gen_ai_client_operation_duration_seconds`, `gen_ai_client_calls_total` and `gen_ai_client_tokens_total` replace the `class` label with `band`, which is `interactive`, `batch` or `background`. `llm_pool_waiters` replaces the `queue` label with `band`. The bundled alerts and dashboards read the new label. Change your own queries that read `class` or `queue`. `hephaestus.agent.capacity.permit-aging` sets how long a call waits before it moves up one band.
