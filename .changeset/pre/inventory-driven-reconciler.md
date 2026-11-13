---
"hephaestus": patch
---

Commit channels use the image inventory of their target commit, so a change to image names does not require another verifier. A runtime rollback or reconciliation restart keeps newer installed verifier tooling, including a version installed by an operator before an upgrade.
