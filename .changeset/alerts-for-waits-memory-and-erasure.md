---
"hephaestus": minor
---

New alerts report model calls that wait too long, workers that lose calls in flight, review stages past their bound, containers killed for memory, erasure and retention jobs that fail or stop, and GitHub redelivery runs that read only part of the deliveries. `HephaestusAgentJobsFailing` now notifies after 5 minutes, not at its first evaluation. `llm_pool_permit_wait_seconds` now has fixed bounds up to 30 minutes, so long waits show and a pool exports far fewer series.
