---
"hephaestus": major
---

Hephaestus 1.0.0 is the stable release of the 1.0 candidates, with the same source and images as the last qualified candidate. From 1.0.0 on, any 1.x release upgrades to any later 1.y release, and only the Helm chart installs it. A review's lease now lives on an agent run, so any server replica takes the review back when its worker is lost.

**Operators:** the cutover is a stopped upgrade with no mixed-version window. Install the agent-sandbox controller from the release's platform files first. The one-host installer does this for you, and it runs sandboxes on gVisor. On another cluster, name a RuntimeClass or use the default runtime, and use a network plugin that enforces NetworkPolicy. An external NATS broker must run NATS Server 2.11 or later. Then move the values that the chart renames, stop every role, back up and verify the backup, upgrade, wait for the migration Job and start the roles. To go back, restore the verified backup and install the previous release. [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0) orders these steps and links the migration entry of each change: this release repeats none of them.
