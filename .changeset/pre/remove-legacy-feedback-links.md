---
"hephaestus": minor
---

A feedback link that Hephaestus posted on a pull request or issue before 0.94.1 no longer opens that feedback. If you follow one, you see "We could not find this page" and a "Go to the home page" link, so you can continue from there. The feedback itself is not deleted. Links posted since 0.94.1 work as before. API clients: `GET /workspaces/{workspaceSlug}/practices/items/legacy-feedback/{artifactKind}/{artifactId}` is removed.
