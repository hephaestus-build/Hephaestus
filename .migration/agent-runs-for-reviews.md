#### 🔴 Stop every role before moving review leases to agent runs

The review leases move from `review_job` to the new `agent_run` table.
The previous release cannot read or renew a lease in the new table.

1. Stop the server, worker, and webhook roles.
2. Back up the database.
3. Upgrade. The migration gives each review job its run.
   A worker that stops normally hands its reviews back to the queue first.
   A review that a worker still held when it stopped is a lost attempt: after the upgrade, the server records its usage and queues it again, or fails it at its retry limit.
4. Start the roles.

Until the review sandbox renews its own run (a later 1.0 change), only the worker that started a review renews its lease.
If that worker stops, the review's running sandbox loses its lease and the review is queued again, instead of finishing on its own.
This is an intermediate state of the 1.0 development, not the 1.0 behaviour.

If you set `hephaestus.agent.max-retries` directly, set `hephaestus.agent.capacity.review-retries` instead. `AGENT_MAX_RETRIES` keeps working.
If the upgrade stops, restore the verified backup before you start the previous release.
