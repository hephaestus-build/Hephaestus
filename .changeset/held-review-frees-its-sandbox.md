---
"hephaestus": patch
---

A review that waits because its workspace's model budget is spent no longer keeps a review sandbox for hours. Hephaestus frees the sandbox within a minute, so other workspaces' reviews can use it. When the budget allows reviews again, the review gets a new sandbox and starts where it would have started. The `agent_sandbox_reaped_total` metric counts these with `reason="held"`.
