---
"hephaestus": minor
---

A workspace can keep its practices in one monitored repository, its practice repository. An admin sets it with `PUT /api/workspaces/{slug}/practice-repository`, commits a practice's folder on the default branch, and moves the practice there. Hephaestus reads the folder first and refuses the move when it is missing or not valid. A practice edited in the repository is read-only in Hephaestus, except for its feedback mode and position, and `get_practice` says where it is edited. Moving it back to Hephaestus keeps its current version, and unsetting the practice repository moves every practice back. Nothing is deleted.
