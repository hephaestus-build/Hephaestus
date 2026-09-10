---
"hephaestus": minor
---

Operators can set the optional `PRACTICE_REVIEW_EXECUTION_CAPTURE_ENABLED=true` to opt in to private practice-review execution archives for evaluation and diagnosis. Archives retain staged inputs, collected outputs, native Pi session transcripts and final provider request bodies, with workspace-administrator access and content-integrity verification. Capture is off by default, follows Context Fabric retention, and distinguishes missing or interrupted evidence from complete capture.

Oversized native sessions are omitted with an explicit incomplete-capture status instead of preventing review results from being collected.
