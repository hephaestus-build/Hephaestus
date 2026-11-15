---
"hephaestus": minor
---

The public REST API leaves out a value it does not have instead of sending `null`. This holds for every response of the public operations, and for the shared types that they return wherever else those types appear, such as item references and account references. Clients in any language read these responses with the generated types.

**Operators:** Update custom API clients that compare these fields with `null`, so that they treat an absent field as missing. The migration guide lists the step.
