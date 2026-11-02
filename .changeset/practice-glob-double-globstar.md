---
"hephaestus": patch
---

A practice review that searches files with a pattern holding more than one `**` now finds every matching file. Before, it missed files whose folders between those `**` parts had a different depth.
