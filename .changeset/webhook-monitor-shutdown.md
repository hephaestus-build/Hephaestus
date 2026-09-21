---
"hephaestus": patch
---

Webhook stream monitoring finishes its active poll during shutdown before broker resources are released, avoiding polling against a closed connection.
