---
"hephaestus": patch
---

A fully captured practice-review trace can be complete even when the review fails. Trace completeness now records finalized, closed capture with no dropped events; the review's exit status, errors and evidence-admission result remain separate. Interrupted or incomplete capture is still reported as incomplete, and historical traces retain their recorded completeness.
