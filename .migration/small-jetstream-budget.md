#### 🔴 Keep the existing broker budget before you apply the small profile

The `small` profile now uses an 8 GiB broker file budget and a 10 GiB claim for fresh storage.
For an existing installation, set `nats.jetstream.maxFileBytes` to its current value in your site values before you upgrade.
Do not shrink a PVC or change an immutable StatefulSet claim template.
Do not lower the broker budget below its stored data.

The profile sets GitHub to 4 GiB and each other provider to 512 MiB, with a maximum age of seven days.
Before you shorten those bounds, verify an off-host backup and test its restore.
Follow [Changing a bound on a stream that already exists](https://docs.hephaestus.build/admin/webhook-ingestion-operations#changing-a-bound-on-a-stream-that-already-exists).
Keep `webhook.stream.allowDestructiveLimitUpdates` false until you approve the data removal.
Enable it only for that change, then return it to false.
A higher limit cannot recover deleted messages.

The server keeps 30-day durable expiry. It reconciles only consumers it uses.
Review orphan consumers separately. A recreated consumer starts with new messages.
