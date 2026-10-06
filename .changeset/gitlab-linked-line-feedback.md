---
"hephaestus": patch
---

On GitLab, practice feedback about specific lines is now posted as a merge request comment that starts with a link to the lines in the reviewed commit, instead of a comment attached to the diff. Review history and admin pages show such feedback as a comment linking to the lines. Before posting each new comment, Hephaestus checks the change's latest synchronized commit; if it differs from the reviewed one, the remaining comments are withheld and the review history says the change was updated first. Comments already posted stay and are still recognized. A push that arrives after this check can still be followed by a comment.
