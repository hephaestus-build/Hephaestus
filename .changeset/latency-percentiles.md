---
"hephaestus": minor
---

Operators can now read the 95th percentile of each API route, of review durations and of conversation turns. `http_server_requests_seconds` has buckets from 25 milliseconds to 10 seconds instead of one bucket at 2 seconds. `hikaricp_connections_acquire_seconds` has buckets from 1 millisecond to 30 seconds. `agent_job_duration_seconds` now has buckets from 1 second to 4 hours, so reviews that run for minutes no longer all land above the top bucket. `mentor_turn_first_token_seconds` and `mentor_turn_duration_seconds` follow the same pattern. A query that reads a bucket of the old lists, other than `le="2.0"`, must change to the new bucket values. A new objective, 95 % of Activity reads within 1 second, has the alerts `HephaestusActivityLatencyBudgetBurnFast` and `HephaestusActivityLatencyBudgetBurnSlow`. Load the rule file of the release to get them. Each label set of the API meter now exports 13 series instead of 5.
