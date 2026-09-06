---
"hephaestus": patch
---

Issue updates are reviewed again. A signal waiting for its coalescing sweep was recorded in a state the
database refused, so the update was retried and then dropped, and no review ever ran for it. Existing
signals are untouched and the next update on an issue is picked up normally.
