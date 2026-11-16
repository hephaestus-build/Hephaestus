---
"hephaestus": minor
---

The chart no longer ships the `preview` profile, which no release workflow used. Use `dev.yaml` for a disposable cluster.

**Operators:** If a Helm command gives `profiles/preview.yaml`, give `profiles/dev.yaml` or the profile of your cluster instead.
