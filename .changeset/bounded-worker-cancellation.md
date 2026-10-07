---
"hephaestus": patch
---

Worker shutdown settles all active reviews before waiting for container cleanup. Container stops run in parallel within one bounded cleanup period, so a slow stop cannot delay settlement of the other reviews. Interrupted reviews retain their recorded observations and usage.
