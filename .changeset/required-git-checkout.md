---
"hephaestus": minor
---

Practice reviews always have Git checkout enabled on the server and worker.

**Operators:** Remove `config.gitCheckoutEnabled` from chart values and retained `site.yaml` files before you upgrade. The chart refuses this retired key.
