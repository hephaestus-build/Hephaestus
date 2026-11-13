---
"hephaestus": patch
---

Reading a repository's files and getting a GitHub installation token no longer hold up the server's other work while they wait on Git or on GitHub. Before, concurrent requests for the same repository version or installation could hold every request thread until they finished.
