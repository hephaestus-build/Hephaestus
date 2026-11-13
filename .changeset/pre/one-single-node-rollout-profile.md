---
"hephaestus": minor
---

Single-node installs use the `small` profile on both 16 GiB and 32 GiB hosts.

**Operators:** Remove `small-16gib` from `HEPHAESTUS_CHART_PROFILE` and remove the `profiles/small-16gib.yaml` values argument before upgrading. Keep `small`.
