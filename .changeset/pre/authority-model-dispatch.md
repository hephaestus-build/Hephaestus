---
"hephaestus": minor
---

When someone leaves a workspace or loses a role, an answer or a review that is running there also stops before its next model call. No more of the work goes to the model under the old access. `llm_proxy_refused_total` counts these calls with `refusal="authority_changed"`, and `authority_refused_total` with `boundary="model_dispatch"`.
