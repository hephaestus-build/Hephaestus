---
"hephaestus": minor
---

The `small` chart profile bounds GitHub deliveries to 4 GiB and each GitLab, Slack and Outline stream to 512 MiB.
All four streams keep deliveries for at most seven days. Byte limits can remove them sooner.
The broker file budget is 8 GiB, with a 10 GiB claim for fresh storage and 30-day durable expiry.

**Operators:** before you upgrade an existing installation, keep its current `nats.jetstream.maxFileBytes` in your site values.
This preserves the existing broker budget and immutable claim template. Do not shrink its PVC.
Verify an off-host backup and test its restore before you shorten stream limits.
Apply smaller stream bounds through the guarded limit-update procedure, then return the destructive-update flag to false.
A higher limit cannot recover deleted messages.
