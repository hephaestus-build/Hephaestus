---
"hephaestus": patch
---

A host that follows a commit with the Helm chart reports the commit as its version, as a Compose host does. Before, it reported `0.0.0-<commit>`, and the promotion's check of the public version failed although the host ran the right commit.
