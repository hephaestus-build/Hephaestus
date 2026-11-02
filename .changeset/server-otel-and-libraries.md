---
"hephaestus": patch
---

The server takes OpenTelemetry 1.67.0, which fixes slow parsing of a crafted trace-state header (GHSA-ccrj-gw8r-854m). BouncyCastle, JGit, Bucket4j, the Slack SDK and the Markdown parser move to their current releases. Operators need no step: the server image changes with the release.
