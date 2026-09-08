---
"hephaestus": patch
---

A host that has not been promoted yet now says so. The deployment reconciler reported the missing channel as an unhandled error, so the first thing a new self-hosted instance logged was a stack trace rather than the sentence naming the environment and the workflow that publishes it.
