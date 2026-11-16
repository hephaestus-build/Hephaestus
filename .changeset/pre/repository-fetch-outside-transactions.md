---
"hephaestus": patch
---

Repository scans no longer block other requests in the workspace while Hephaestus fetches the repository from GitHub or GitLab, and scans and review jobs no longer hold database connections during that fetch.
