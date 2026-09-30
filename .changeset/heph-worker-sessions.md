---
"hephaestus": minor
---

Heph conversations now run their sandboxes on connected workers. Web chat and Slack keep their admission, context and thread history on the application server. When all workers are full, Heph reports that it is busy and you can retry. A worker drain ends its conversations; your next message restores the saved thread on an available worker.

**Operators:** Configure at least one worker with `HEPHAESTUS_HUB_URL` and `HEPHAESTUS_WORKER_REGISTRATION_TOKEN`. Heph has no in-process sandbox fallback. The application server can run with `hephaestus.runtime.worker.enabled=false` without a Docker client.
