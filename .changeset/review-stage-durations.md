---
"hephaestus": minor
---

The worker now measures how long each stage of a review attempt takes: the evidence capture, the sandbox that runs the practice sessions, the wait for a composition slot, and the composition. Read them in `agent_job_stage_duration_seconds` by `stage` and `outcome`, or as `practice_review.<stage>` spans in a trace. The admin guide's observability page also shows how to read the duration of result admission.
