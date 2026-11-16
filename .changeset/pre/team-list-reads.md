---
"hephaestus": patch
---

The team list and a team's label changes read far fewer database rows: a team's repositories and members now load in separate reads instead of one read that paired every repository with every member.
