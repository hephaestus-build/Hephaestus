---
"hephaestus": patch
---

After a stopped window deletes the sandbox claims, reviews and conversations start again on the server's first sandbox reaper pass. Before, a ready sandbox whose claim was gone kept its review or conversation waiting until the sandbox's shutdown time. The metric `agent_sandbox_reaped_total` counts these sandboxes with `reason="lost"`.
