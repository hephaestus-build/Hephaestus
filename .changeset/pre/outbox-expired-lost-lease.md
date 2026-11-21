---
"hephaestus": patch
---

An outbox action whose server stopped mid-attempt is no longer retried after its deadline. Before, another server took it again however late, so a product feedback draft could still call the model after its deadline. Now an expired action that wrote nothing ends as expired. An action that may already have written is still checked against the provider first, as before.
