---
"hephaestus": patch
---

A review no longer treats an issue number inside an HTML comment of the pull request description as
a linked work item. Merge request templates often keep a commented example such as
`<!-- Example: #12: Add login -->`; on one cohort two thirds of all merged changes carried it, and
every review of them was handed an unrelated issue to judge the change against.
