---
"hephaestus": minor
---

Models that share provider capacity can now share one call pool across workers and workspaces. Reserve capacity for conversations, serve background work fairly, and set shared request and token rates. Reviews wait for admission before they start a sandbox. Cancellation keeps capacity counted until the model connection closes.

The optional `AGENT_MAX_ACTIVE_COMPOSITIONS` setting bounds loaded compositions per worker and defaults to 4. `AGENT_MAX_CONCURRENT_COMPOSITIONS` continues to bound all review attempts.

Queued review claims take part in fair scheduling before a sandbox starts. Unused first-call reservations recover when a worker stops renewing them. Active calls retain their fenced permits until their transport closes, including cancellation after JSON response headers.

A new review cannot hide the claim that owns the pool's next turn. A first call to another pool releases only its unused primary reservation before it waits.
Refunds for unused reservations preserve scheduling order, so an older composition retains its turn.

Queued calls keep their scheduling position while they wait for local admission. Completed JSON answers retain their usage charge if capacity is revoked before settlement. Exhausted retries record withheld feedback without waiting for a composition slot.

Changing a busy call pool returns a conflict with the `llm-call-pool-in-use` problem type and a message that explains when to retry.
