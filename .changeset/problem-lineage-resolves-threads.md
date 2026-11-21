---
"hephaestus": minor
---

Hephaestus now resolves the thread of its own line note or file note on a pull request or merge request when a later review shows that every problem the note named is fixed. The review must read the new version in full, and the code that the note quoted must be gone. If a problem comes back, Hephaestus reopens the thread. A thread that a person resolves, reopens or replies in is left to people for good. A new alert, `HephaestusFeedbackThreadChangeFailing`, fires when GitHub or GitLab does not confirm such a change.
