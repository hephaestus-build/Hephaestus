---
"hephaestus": minor
---

Instance admins can put models that share one provider endpoint, such as a GPU gateway that other systems also use, into a call pool. A pool limits the model calls in flight across all workers and workspaces, keeps a reserve for Heph conversations that reviews never use, and pauses all its models when the provider answers with a rate limit, for as long as its `Retry-After` asks. Set up a pool through the instance admin API; a workspace admin sees their own model's pool. Models in no pool behave as before. Optional: `HEPHAESTUS_LLM_CALL_POOLS_PERMIT_LEASE` (default 1 minute) and `HEPHAESTUS_LLM_CALL_POOLS_MAX_COOLDOWN` (default 5 minutes).
