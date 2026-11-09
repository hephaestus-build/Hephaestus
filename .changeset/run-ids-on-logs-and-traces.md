---
"hephaestus": patch
---

You can follow one agent run through the server log and its trace. Each record of a review attempt names its run, the epoch of its claim and its subject (`run.id`, `run.epoch`, `subject.key`), and so does the attempt's trace span. When the run reaper takes back a lease, or when an outbox action fails or ends uncertain, the server writes a structured event with a stable `event.name` and the run or action it concerns (`agent.run.requeued`, `agent.run.ended`, `agent.run.reaper.failed`, `action.attempt.failed`, `action.ended.uncertain`). The events name no person and no message content. A log query that matched the message text of these reaper and outbox lines should match their `event.name` now; the observability guide lists the events and their fields.
