---
"hephaestus": patch
---

When Hephaestus cannot be reached, for example while it restarts, the web app no longer treats you as signed out or as lacking access. The header says "Hephaestus is not reachable right now" and offers Try again. It also picks your session up again by itself once the server answers. Pages show their error page with Retry instead of the visitor's page or a redirect away from workspace administration. A server that does not answer within 10 seconds counts as unreachable.
