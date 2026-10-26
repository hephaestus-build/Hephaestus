---
"hephaestus": minor
---

A practice script can no longer report Met or Does not apply on coverage that it typed itself. Hephaestus now measures what the script read, so a script that ignored a failed read, or quoted a source without reading it, gets no Met.

**Operators:** a custom practice script that returns a result with `coverage` now stops as an invalid result. Remove `coverage` from the result. Scripts that return what `ctx.review` returned need no change.
