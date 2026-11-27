---
"hephaestus": patch
---

A pull or merge request review now always keeps its results when its copy for the work place cannot be prepared. Before, a copy whose inputs could not be read undid the whole review, and a second seal of the same review that raced the first could fail. Operators can see each decision in `agent_job_work_place_handoff_total`, and the new `HephaestusWorkPlaceHandoffFailing` alert fires when a copy fails. Feedback on the work still reaches developers as before.
