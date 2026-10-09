---
"hephaestus": patch
---

Release evidence capture resources when cleanup fails, so later reviews on the same worker thread can start. Keep the original failure and any cleanup errors visible.
