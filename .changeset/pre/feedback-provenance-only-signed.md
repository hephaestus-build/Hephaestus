---
---

Refactor with no user-facing effect. Only the code that signs feedback can set its provenance record. An architecture test now refuses other code that sets it, including through the feedback builders and constructors.
