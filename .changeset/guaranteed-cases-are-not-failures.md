---
"hephaestus": patch
---

Error-handling review standards distinguish defensive checks of values guaranteed by their type or API contract from paths that can fail. Failures from runtime input, responses and stored data remain in scope.
