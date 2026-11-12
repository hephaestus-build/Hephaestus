---
"hephaestus": patch
---

Review sandboxes that prepare, compose or hand in results at the same moment no longer hold up the server's other requests. Before, their database work could hold the request threads that other requests needed.
