---
"hephaestus": minor
---

Feedback that Hephaestus posts on GitHub and GitLab now carries a signed provenance record in a hidden line of each comment. The record says that Hephaestus generated the words, when, with which release and which model, and it names no person and no workspace. Your instance publishes the keys that check it at `/.well-known/hephaestus-provenance-keys.json`, so any tool can confirm that your instance signed the record. The signature alone does not show that the words next to it are unchanged. Feedback on practice pages and in conversations does not carry a record yet. The system key seals the signing key, so the system key's backup and rotation cover it. The record names the model that wrote the feedback, so anyone who can read a comment can see that model's ID.
