---
"hephaestus": minor
---

Operators can now see how long each model call takes, how many tokens it uses and how it ends, for each call pool. `gen_ai_client_operation_duration_seconds` measures a call from its pool's grant to its end. `gen_ai_client_tokens_total` counts input, output and cached tokens. `gen_ai_client_calls_total` counts calls by outcome, including `refused` when a model declines to answer and `length` when an answer hits its limit. `llm_proxy_refused_total` counts the calls that the proxy refuses before they reach a provider, by reason.

**Operators:** three metrics are gone. `llm_proxy_duration_seconds` is replaced by `gen_ai_client_operation_duration_seconds`. `sandbox_gateway_calls_in_flight_refused_total` is replaced by `llm_proxy_refused_total{refusal="calls_in_flight"}`. `llm_proxy_budget_blocked_total` is replaced by `llm_budget_blocked_total{surface="model_call"}`, which keeps the `cap` label. Change your own alerts and dashboards that read them.
