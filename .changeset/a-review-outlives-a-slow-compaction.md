---
"hephaestus": patch
---

Practice reviews cancel context compaction when its turn expires and wait for the session to stop
before starting another turn. A slow compaction no longer causes later turns to be refused as busy.
