---
"hephaestus": patch
---

Eight bundled practices now say when they apply to a change: "Keep functions small and single-purpose", "Leave the code clean and comment the why", "Log through the platform logger, not print", "Handle errors instead of swallowing them", "Validate inputs and edge cases at the boundary", "Avoid crashing on recoverable problems", "Avoid insecure defaults and over-broad permissions" and "Track generated files only when justified". When a change has nothing that one of them covers, such as no added or substantially rewritten function, no diagnostic call, or no added or modified file, the review records that the practice does not apply, instead of judging work it does not cover. A workspace that customized one of these practices keeps its own text and sees the new wording as a catalog update to accept or decline.
