---
"hephaestus": patch
---

Hephaestus preserves feedback already posted beside code. A retry creates a note only when the earlier attempt is positively known not to have created it. When a write is unconfirmed, Hephaestus looks for the existing copy without creating another. Prior copies count as delivered only when their author, exact text and required location match. Ordinary fallback notes use their stated file and line rather than a native code anchor. Summary recovery also verifies Hephaestus's own exact comment, so a copied marker cannot mark a review as delivered.
