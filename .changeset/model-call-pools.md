---
"hephaestus": minor
---

Models that share provider capacity can now share one call pool across workers and workspaces. Reserve capacity for conversations, serve background work fairly, and set shared request and token rates. Reviews wait for admission before they start a sandbox. Cancellation keeps capacity counted until the model connection closes.

The optional `AGENT_MAX_ACTIVE_COMPOSITIONS` setting bounds loaded compositions per worker and defaults to 4. The existing total-attempt limit still leaves room for reviews.
