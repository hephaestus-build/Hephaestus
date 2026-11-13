---
"hephaestus": minor
---

Each practice review now runs in an agent sandbox of its own. When a review is due, the server starts the review's sandbox. The sandbox measures the review, then composes its feedback in the same sandbox. A review sandbox reaches only the server's gateway, and every model call and result that it sends counts only for the review it runs and only while that run holds its lease.

Reviews use the agent-sandbox controller and the chart's sandbox templates, which the chart already installs. `SANDBOX_MAX_CONCURRENT` still sets how many review sandboxes run at once. The new alert `HephaestusAgentSandboxDispatchFailing` fires when the server keeps failing to start the sandboxes of due reviews. `HephaestusAgentSandboxUnisolated` fires for a sandbox whose network policy did not hold in time.
