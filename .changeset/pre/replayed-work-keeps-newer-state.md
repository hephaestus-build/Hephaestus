---
"hephaestus": patch
---

Replayed webhook events preserve newer issues, pull requests and Outline documents. Webhook timestamp ties keep the stored work unless a push proves the next head. A fresh provider read can settle issue updates that share a timestamp. Outline checks current provider state before it applies an undated delete or archive.
