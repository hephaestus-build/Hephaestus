---
"hephaestus": patch
---

Public activity pages load faster in large workspaces. A change, such as hiding a person, shows the next time the page loads, not up to a minute later.
A shared cache in front of Hephaestus no longer keeps public activity pages, so every anonymous page load reaches the server, within the public activity rate limit.
