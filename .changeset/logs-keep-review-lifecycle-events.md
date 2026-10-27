---
"hephaestus": patch
---

The worker log keeps each review's start, end and preparation failure. These records were dropped before. Each log record of every runtime role now names the release that the deployment runs, not `0.0.0-development`.
