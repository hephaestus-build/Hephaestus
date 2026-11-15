---
"hephaestus": patch
---

Reviews of pull requests in a large repository no longer exhaust the server's memory through their model calls. Each model call from a review sandbox read the review's whole captured file list, which is many megabytes for a large repository, so several reviews at once could fill the server's memory within seconds. A model call now reads only what it needs.
