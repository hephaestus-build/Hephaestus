---
"hephaestus": minor
---

Models for practice scripts are gone, because a practice script now makes no direct model call. **AI models** no longer offers decision, embedding or reranking assignments, and a connection can no longer use the Decisions, Embeddings or Rerank API. **AI usage** no longer has the table of these models by practice, and a review's **Practice scripts** section no longer has the **Script models** and **Script calls** columns. Past usage of these models stays in **By use** and **By day**.

**Operators:** The upgrade deletes the decision, embedding and reranking assignments, and the instance connections that use the Decisions, Embeddings or Rerank API, with their models. `PRACTICE_REVIEW_DIRECT_MODEL_TOKENS_PER_ATTEMPT`, `PRACTICE_REVIEW_DIRECT_MODEL_SEARCH_UNITS_PER_ATTEMPT` and the `llm.proxy.practice_script.unattributed` metric are gone. Remove the two variables from your configuration if you set them. Without the 4 direct script calls, an attempt has at most 170 model calls in flight, and the default of `SANDBOX_API_REQUESTS_PER_MINUTE` falls from 696 to 680. Stop every role for this upgrade: it drops the table that the old proxy writes script usage to.
