---
"hephaestus": patch
---

**Make private** on a public product feedback issue now moves the issue through the same outbox as filing. When GitHub's answer to the move is lost, Hephaestus says so, then looks for the moved issue in the private repository and finishes the move, instead of moving it again.
