---
"hephaestus": patch
---

A model whose **Context window** and **Max output tokens** are not set now gets them from its provider, so new practice reviews and product-feedback drafts on it keep running after the upgrade. Work admitted before its limits were read stays refused; start it again. Hephaestus reads them when you test a saved connection, and when a server starts for each enabled model that has no output limit. It reads Anthropic's and OpenRouter's model listings, vLLM's `max_model_len`, LiteLLM's `/model/info` and the `num_ctx` that Ollama's `/api/show` reports, and ships the published limits of OpenAI's current models. When only the context window is known, or you set only the context window, the output limit is a quarter of it, at most 16,384 tokens. A value that you set always wins and is never overwritten: clear the field to use the provider's value again. The model form shows each value and where it comes from.

A model that still has no output limit is marked **Limits missing** in **AI providers**, and the `llmModelLimits` component of `/actuator/health` names it. Its calls are refused, as before, until you set **Max output tokens** or **Context window**. A model that had only a context window now runs with a quarter of it as its output limit, at most 16,384 tokens, instead of the whole window; set **Max output tokens** to allow more.
