---
"hephaestus": patch
---

Practice reviews retry a temporarily busy result upload rather than treating it as completed. Missing files in historical citations are reported as missing evidence instead of failed repository operations. Repository citations show the full commit identity without requiring a hover.

Long-running repository preparation no longer loses its temporary files to stale-file cleanup.
