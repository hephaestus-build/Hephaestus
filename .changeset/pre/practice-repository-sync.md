---
"hephaestus": minor
---

A workspace admin can sync practices from the practice repository's default branch with `POST /api/workspaces/{slug}/practice-repository/sync` or the MCP tool `sync_practice_repository`. A new folder becomes a practice with feedback mode Off, a changed folder adds a version, and a draft practice with the folder's name goes into use with feedback mode Off. A practice edited in Hephaestus does not change, and an invalid folder changes nothing. A practice edited in the repository whose folder is gone is reviewed by nobody and keeps its history. Syncing the same commit again changes nothing. The sync report gives the status of each folder, each problem with its file and line, and whether a trial ran each new version, and `get_practice_repository` returns the last one.
