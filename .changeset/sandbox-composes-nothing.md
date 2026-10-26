---
"hephaestus": minor
---

The review sandbox no longer carries the feedback composer. A sandbox only measures and admits results. The worker composes all feedback after the sandbox ends. The internal API routes `POST /internal/llm/prepare-feedback` and `POST /internal/llm/public-feedback-history`, which only the review sandbox called, are removed. Run a server and review sandbox image of the same release. No configuration changes.
