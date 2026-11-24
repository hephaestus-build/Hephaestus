---
"hephaestus": minor
---

The API now creates and lists a personal space for each person: a workspace that only they can enter, which belongs to no team. `GET /api/user/spaces` lists it first, then the team workspaces the person belongs to, and creates it on the first call. Instance administrators cannot open it, and it ends when the person deletes their account. Nothing in the web app opens it yet.
