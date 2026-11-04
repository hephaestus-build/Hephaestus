---
"hephaestus": patch
---

The server's check that a database read stays inside one workspace is stricter for repository, issue, pull request, commit and team data. Such a read must now name the workspace it reads for, and a read that only mentions a workspace no longer passes the check.
