#### 🔴 Three model call metrics have new names

**Affected**: deployments with their own alerts or dashboards on the LLM proxy metrics. The shipped alert rules read none of them.

| Old metric | New metric |
| --- | --- |
| `llm_proxy_duration_seconds` | `gen_ai_client_operation_duration_seconds`, by `pool` and `class`, from the pool's grant to the end of the call |
| `sandbox_gateway_calls_in_flight_refused_total` | `llm_proxy_refused_total{refusal="calls_in_flight"}` |
| `llm_proxy_budget_blocked_total` | `llm_budget_blocked_total{surface="model_call"}`, with the same `cap` label |

A query on an old name does not fail. It returns no series, so an alert built on it stops firing without a sign.

**Migration**: change those queries when you upgrade. The old duration included the wait for a pool permit, which `llm_pool_permit_wait_seconds` measures on its own.
