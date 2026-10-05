---
"hephaestus": patch
---

The bundled practices judge colors, commits, review follow-up and readiness more accurately. Workspaces that adopted them see the corrections as an update to accept or decline.

- A color is judged by what it resolves to. SwiftUI's standard colors, such as `.green`, count as adapting to light and dark appearance. UIKit's fixed constants, such as `UIColor.green`, and colors built from fixed values still count as fixed. A color your app defines is judged from its definition.
- When the code changes of a pull request could not be read, Hephaestus skips the commit practices for that review instead of recording an open question. The commit history it reads now holds every commit of the pull request, not only the newest 500.
- An explicit request to hold the merge counts as an open request, even outside a review thread. Optional advice for later work does not. A thread resolved before the merge, or a draft marked ready, needs no further date.
- A merge request template's checklist left as supplied no longer counts as saying the work is unfinished. What you write about unfinished work still does.
- To tell a small chore from a feature, the handoff practice can read the changed lines, not only the file names.
- On GitLab, an approval keeps the date GitLab itself records for it. Hephaestus no longer claims which commit an approval was given on, since GitLab does not say.
