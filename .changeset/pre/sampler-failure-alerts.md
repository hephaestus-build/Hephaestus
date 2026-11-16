---
"hephaestus": patch
---

Two new alerts warn when the agent run gauges or the outbox backlog gauges stop refreshing: `HephaestusAgentRunsUnsampled` and `HephaestusOutboxUnsampled`. Before, a stalled queue or outbox could hide behind the last healthy value and fire no alert while its sampler failed.
