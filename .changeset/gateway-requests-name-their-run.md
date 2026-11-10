---
"hephaestus": patch
---

Each request that an agent sandbox makes to the gateway is now traceable to its sandbox and run. Every log record of the request names the sandbox, its subject and its workspace (`sandbox.name`, `subject.key`, `workspace.id`). A request for one run also names the run and the epoch of its claim (`run.id`, `run.epoch`), but only after the gateway has checked that the run belongs to the sandbox. The request's trace span carries the same attributes.
