---
"hephaestus": minor
---

Practice scripts now have a small public API that holds for a major version: `@hephaestus/practice-scripts`, with `/scm`, `/chat` and `/docs`. A script can import nothing else. A script that imports an internal file stops, names the import, and its practice is withheld with the reason "Not a runnable script". The bundled scripts move to this API and send the same input to a review. `leaves-the-code-clean-with-intent-revealing-comments` no longer stops on a change with an Objective-C file.

**Operators:** rewrite any custom practice script for this API. The upgrade notes list the changes and a query that finds such scripts.
