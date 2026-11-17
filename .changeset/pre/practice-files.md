---
"hephaestus": minor
---

Workspace admins can download a practice as a folder of files: **More actions → Download as files** on a practice's row in **Practices**. The folder holds `practice.md` (YAML front matter, then the criteria), and the guide, visual and practice script when the practice has them. An MCP client can export a practice with `export_practice` and check a folder with `check_practice_files`, which reports each problem with its file, line and column, saves nothing and calls no model. [Practice files](https://docs.hephaestus.build/user/practice-files) describes the format.
