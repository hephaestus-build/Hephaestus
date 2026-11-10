---
"hephaestus": major
---

Hephaestus 1.0.0 is the stable release of the 1.0 candidates, with the same source and images as the last qualified candidate. From 1.0.0 on, any 1.x release upgrades to any later 1.y release, and only the Helm chart installs it.

**Operators:** the cutover is a stopped upgrade with no mixed-version window. Follow [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0): it orders the steps and names the migration entry of each change. To go back, restore the verified backup and install the previous release.
